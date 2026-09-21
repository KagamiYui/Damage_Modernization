package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.api.zone.DamagePipeline;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;

/**
 * 伤害管线的接入点：把原版的单一伤害数值替换为四乘区合成结果。
 *
 * <h2>接管流程</h2>
 * <ol>
 *   <li><b>{@link CriticalHitEvent}</b>（攻击发起时）：
 *       取消原版「跳跃下劈必定暴击 ×1.5」的判定，改由
 *       {@code crit_chance} / {@code crit_damage} 属性驱动；
 *       同时记录本次攻击<b>尚未被蓄力与暴击乘算</b>的原始基础攻击力。</li>
 *   <li><b>{@link LivingIncomingDamageEvent}</b>（伤害进入减免前）：
 *       交给 {@link DamagePipeline} 用乘区模型重新合成伤害并写回，
 *       随后交还原版的护甲/附魔/盾牌/吸收流程。</li>
 * </ol>
 *
 * <h2>为什么选择 LivingIncomingDamageEvent</h2>
 * 该事件在 {@code LivingEntity.hurt()} 中、任何减免计算<b>之前</b>触发，
 * 且提供可变的伤害容器。这意味着我们改造的是「伤害的构成」，
 * 而不是在减免之后做一次粗暴的覆盖，因此护甲、抗性、盾牌等原版机制全部保持有效。
 */
@EventBusSubscriber(modid = DamageModernization.MODID)
public final class DamageEventHandler {

    private DamageEventHandler() {
    }

    /**
     * 接管暴击判定，并记录原始基础攻击力。
     *
     * <p>原版在 {@code Player.attack()} 中先算出蓄力系数与暴击，再相乘。
     * 我们在这里把暴击倍率压回 1.0（取消原版暴击的乘算），
     * 同时把「未乘算的基础攻击力」记录下来，供伤害事件重建。
     *
     * <p>使用 {@link EventPriority#HIGHEST} 以确保在其他模组读取暴击状态之前完成接管。
     *
     * @param event 暴击事件
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCriticalHit(CriticalHitEvent event) {
        if (!DamagePipeline.isModelEnabled()) {
            return;
        }

        Player player = event.getEntity();
        Entity target = event.getTarget();

        // 读取攻击力（此时仍未经过蓄力与暴击乘算）。
        double attackPower = BaseAttackPowerConverter.resolveBaseAttackPower(player);

        // 由 crit_chance 属性决定是否暴击，取代原版的「跳跃下劈」判定。
        boolean crit;
        if (Config.ENABLE_CRIT_ZONE.getAsBoolean()) {
            double critChance = player.getAttributeValue(DMAttributes.CRIT_CHANCE);
            crit = player.getRandom().nextDouble() < critChance;

            // 取消原版暴击的伤害乘算：伤害交由我们的暴击乘区统一处理，
            // 否则会出现原版 ×1.5 与我们的暴击区叠加的双重暴击。
            event.setCriticalHit(false);
            event.setDamageMultiplier(1.0F);
        } else {
            crit = false;
        }

        // 记录上下文，供后续的伤害事件取用。
        AttackContext.push(attackPower, crit, target.getId());

        if (Config.LOG_ZONE_CALCULATION.getAsBoolean()) {
            DamageModernization.LOGGER.info(
                    "[DM] attack recorded: power={} crit={}", attackPower, crit);
        }
    }

    /**
     * 用乘区模型重新合成伤害。
     *
     * @param event 受伤事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!DamagePipeline.isModelEnabled()) {
            return;
        }

        LivingEntity victim = event.getEntity();
        Entity attackerEntity = event.getSource().getEntity();

        // 仅处理「生物直接攻击生物」的情况。
        // 投射物（箭、火球）的来源实体虽然也是生物，但它们有独立的基础伤害，
        // 故只对直接近战攻击应用攻击力属性。
        boolean isMeleeAttack = attackerEntity instanceof LivingEntity
                && event.getSource().getDirectEntity() == attackerEntity;

        if (isMeleeAttack) {
            LivingEntity attacker = (LivingEntity) attackerEntity;

            // 取出在 CriticalHitEvent 中记录的上下文。
            AttackContext context = AttackContext.pop(victim.getId());

            double baseAttackPower;
            boolean crit;

            if (context != null) {
                // 玩家近战：使用记录的原始基础攻击力，暴击状态已确定。
                baseAttackPower = context.originalAttackPower();
                crit = context.critical();
            } else {
                // 生物近战（或上下文缺失）：读取攻击者属性，并即时掷骰暴击。
                baseAttackPower = BaseAttackPowerConverter.resolveBaseAttackPower(attacker);
                crit = rollCrit(attacker);
            }

            DamageContext ctx = DamagePipeline.createMeleeContext(
                    event.getSource(), attacker, victim, baseAttackPower, crit);
            double finalDamage = DamagePipeline.compose(ctx);

            applyDamage(event, finalDamage, ctx, "melee");
            return;
        }

        // 环境伤害：以原始伤害直接充当攻击力区，但仍经过伤害提升/倍率/暴击乘区，
        // 使「所有伤害统一走四乘区」这一目标成立。
        if (Config.APPLY_ZONES_TO_ENVIRONMENTAL_DAMAGE.getAsBoolean()) {
            DamageContext ctx = DamagePipeline.createEnvironmentalContext(
                    event.getSource(), victim, event.getOriginalAmount());
            double finalDamage = DamagePipeline.compose(ctx);

            applyDamage(event, finalDamage, ctx, "environmental");
        }
    }

    /**
     * 实体死亡时清理缓存，避免内存泄漏。
     *
     * @param event 死亡事件
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        AttackContext.clear();
        BaseAttackPowerConverter.forget(event.getEntity());
    }

    /**
     * 按属性即时判定暴击。
     *
     * <p>用于没有经过 {@link CriticalHitEvent} 的攻击者（主要是生物）。
     *
     * @param attacker 攻击者
     * @return 是否暴击
     */
    private static boolean rollCrit(LivingEntity attacker) {
        if (!Config.ENABLE_CRIT_ZONE.getAsBoolean()) {
            return false;
        }
        double critChance = attacker.getAttributeValue(DMAttributes.CRIT_CHANCE);
        return attacker.getRandom().nextDouble() < critChance;
    }

    /**
     * 把合成结果写回伤害容器。
     *
     * @param event       受伤事件
     * @param finalDamage 乘区模型算出的最终伤害
     * @param ctx         伤害上下文，用于日志
     * @param kind        来源类型，用于日志
     */
    private static void applyDamage(LivingIncomingDamageEvent event,
                                    double finalDamage,
                                    DamageContext ctx,
                                    String kind) {
        if (Config.LOG_ZONE_CALCULATION.getAsBoolean()) {
            DamageModernization.LOGGER.info(
                    "[DM] {} damage: raw={} -> {} (base={}, attackZone={}, amp={}, mult={}, crit={})",
                    kind, event.getOriginalAmount(), finalDamage,
                    ctx.baseAttackPower(),
                    ctx.baseAttackPower() * (1.0D + ctx.attackPowerPercent()) + ctx.attackPowerFlat(),
                    ctx.damageAmplifier(), ctx.damageMultiplier(),
                    ctx.isCritical() ? ctx.critDamage() : 1.0D);
        }

        if (!Double.isFinite(finalDamage) || finalDamage < 0.0D) {
            return;
        }

        event.setAmount((float) finalDamage);
    }
}
