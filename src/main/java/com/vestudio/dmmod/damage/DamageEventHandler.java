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

        // 外部暴击数值（星辉的 perk）。
        // 它们只<b>提供数值</b>，判定权仍在本 mod：这里把星辉的概率加进我们的骰子，
        // 它自己那一次掷骰的结果则由 onCriticalHitFinalize 在收尾时压回去，
        // 保证同一份概率只算一次。
        double externalCritChance = AstralCompat.extraCritChance(player);
        double externalCritDamage = AstralCompat.extraCritDamageBonus(player);

        // 由 crit_chance 属性决定是否暴击，取代原版的「跳跃下劈」判定。
        boolean crit;
        if (Config.ENABLE_CRIT_ZONE.getAsBoolean()) {
            double critChance = player.getAttributeValue(DMAttributes.CRIT_CHANCE)
                    + externalCritChance;
            crit = player.getRandom().nextDouble() < critChance;

            // 取消原版暴击的伤害乘算：伤害交由我们的暴击乘区统一处理，
            // 否则会出现原版 ×1.5 与我们的暴击区叠加的双重暴击。
            event.setCriticalHit(false);
            event.setDamageMultiplier(1.0F);
        } else {
            crit = false;
        }

        // 记录上下文，供后续的伤害事件取用。
        AttackContext.push(attackPower, crit, target.getId(), externalCritDamage);

        if (Config.LOG_ZONE_CALCULATION.getAsBoolean()) {
            DamageModernization.LOGGER.info(
                    "[DM] attack recorded: power={} crit={} (extCritChance={}, extCritDamage={})",
                    attackPower, crit, externalCritChance, externalCritDamage);
        }
    }

    /**
     * 在暴击事件的<b>收尾阶段</b>把暴击状态再压回去一次。
     *
     * <h2>为什么必须在最后再压一次</h2>
     * 星辉（Astral Sorcery）在 {@code EventPriority.HIGH} 上<b>自己掷骰</b>并
     * {@code setCriticalHit(true)}，它的暴击伤害处理器又在 {@code LOW} 上
     * {@code setDamageMultiplier(...)}——这两步都发生在我们的 {@code HIGHEST} <b>之后</b>。
     *
     * <p>而 {@code CriticalHitEvent} <b>不实现 {@code ICancellableEvent}</b>：
     * 事件不可取消，我们无法阻止它的监听器运行。
     *
     * <h2>不压回会怎样</h2>
     * 本 mod 已经把星辉的暴击<b>数值</b>并入了自己的骰子（见 {@link AstralCompat}），
     * 若再让它那一次掷骰生效，同一份暴击概率就会被<b>算两次</b>——
     * 例如「属性 5% + 星辉 25%」本应是 30%，实际会接近 47%。
     *
     * <p>因此这里把它压回去：<b>数值由本 mod 计入一次，星辉的骰子结果不参与判定。</b>
     *
     * <h2>与伤害的关系</h2>
     * 本 mod 的伤害取自 {@link AttackContext} 里记录的判定结果，本就不受这个标志影响。
     * 这里压回是为了不让它泄漏出去——原版的暴击粒子/音效，
     * 以及任何读取 {@code isCriticalHit()} 的第三方。
     *
     * @param event 暴击事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCriticalHitFinalize(CriticalHitEvent event) {
        if (!DamagePipeline.isModelEnabled()) {
            return;
        }
        if (!Config.ENABLE_CRIT_ZONE.getAsBoolean()) {
            return;
        }

        // 无条件压回：本 mod 是近战暴击的唯一判定者。
        event.setCriticalHit(false);
        event.setDamageMultiplier(1.0F);
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

            // 把本次攻击携带的外部暴伤加成（星辉的 perk）并入暴击区。
            // 只有玩家近战才有 AttackContext，因此生物走不到这里。
            if (context != null) {
                ctx.addExternalCritDamageBonus(context.externalCritDamageBonus());
            }

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
     * 装备变化时立即刷新基础攻击力。
     *
     * <p>否则镜像只在<b>下一次伤害结算</b>时才更新，期间
     * {@code base_attack_power} 会保留上一把武器的数值——
     * 属性面板会显示陈旧数据，切到空手后也可能读到上一把武器的攻击力。
     *
     * <p>只在服务端执行：数值由服务端权威计算并同步给客户端，
     * 避免两端各自维护一份镜像状态而产生分歧。
     *
     * @param event 装备变化事件
     */
    @SubscribeEvent
    public static void onEquipmentChange(net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent event) {
        if (!DamagePipeline.isModelEnabled()) {
            return;
        }

        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }

        // 重新推导镜像，使基础攻击力尽早反映当前武器。
        // 即便此刻原版属性尚未结算完（读到中间态），
        // 下一次伤害结算也会再次推导，因此不会影响实际伤害。
        BaseAttackPowerConverter.resolveBaseAttackPower(entity);
    }

    /** 刷新间隔：20 tick = 1 秒。 */
    private static final int REFRESH_INTERVAL_TICKS = 20;

    /**
     * 服务端每秒刷新一次所有在线玩家的基础攻击力。
     *
     * <h2>为什么不能只依赖事件</h2>
     * 镜像（把原版攻击伤害的修饰符搬到基础攻击力上）原本只在
     * 「伤害结算」与「装备变化事件」时更新。前者意味着<b>必须打中目标</b>
     * 才会刷新，后者在装备属性结算流程中触发、时机偏早，
     * 都可能让 {@code base_attack_power} 停留在旧值——
     * 属性面板因此要等到「换物品」或「攻击过」之后才更新。
     *
     * <p>改由服务端<b>每秒</b>权威刷新后，数值始终跟得上当前武器，
     * 再经原有的属性同步下发到客户端，面板即可及时反映。
     *
     * <h2>为什么不每 tick 刷新</h2>
     * 镜像涉及修饰符的增删与属性脏标记，每 tick 执行属于无谓开销；
     * 玩家的换装与状态变化远不到每 tick 的频率，
     * 1 秒的延迟对属性面板而言已足够，也避免了频繁的网络同步。
     *
     * <p>刷新是幂等的（先按前缀清除旧镜像再重建），重复调用安全。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!DamagePipeline.isModelEnabled()) {
            return;
        }

        // 用服务端自身的 tick 计数做节流，每 20 tick（1 秒）执行一次。
        if (event.getServer().getTickCount() % REFRESH_INTERVAL_TICKS != 0) {
            return;
        }

        for (net.minecraft.server.level.ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            BaseAttackPowerConverter.resolveBaseAttackPower(player);

            // 生命值：优先走数据驱动的公式；数据缺失时退回内置计算器。
            if (ZoneIds.healthZonesPresent()) {
                HealthFormulaEvaluator.evaluate(player);
            } else {
                HealthCalculator.resolveMaxHealth(player);
            }

            // 护甲体系：护甲与盔甲韧性同样走「基础值 → 百分比/固定值 → 写回原版属性」。
            // 乘区缺失时什么都不做，保持原版数值。
            if (ZoneIds.armorZonesPresent()) {
                ArmorFormulaEvaluator.evaluate(player);

                // 调试：把服务端算出来的原始值也打一份。
                // 与客户端那段 "[DM] raw armor state (client)" 对照，
                // 就能判断「面板显示 0」到底是服务端没算出来、还是没同步过去。
                if (Config.LOG_ZONE_CALCULATION.getAsBoolean()
                        && event.getServer().getTickCount() % 100 == 0) {
                    logArmorState(player);
                }
            }
        }
    }

    /**
     * 打印服务端护甲相关属性的原始内部值（调试用）。
     *
     * @param player 玩家
     */
    private static void logArmorState(net.minecraft.server.level.ServerPlayer player) {
        var vanilla = player.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        var base = player.getAttribute(DMAttributes.BASE_ARMOR);
        DamageModernization.LOGGER.info(
                "[DM] raw armor state (server): vanilla_armor base={} total={}, "
                        + "base_armor base={} total={} modifiers={}, "
                        + "armor_percent={}, armor_flat={}",
                vanilla == null ? -1.0D : vanilla.getBaseValue(),
                vanilla == null ? -1.0D : vanilla.getValue(),
                base == null ? -1.0D : base.getBaseValue(),
                base == null ? -1.0D : base.getValue(),
                base == null ? -1 : base.getModifiers().size(),
                player.getAttributeValue(DMAttributes.ARMOR_PERCENT),
                player.getAttributeValue(DMAttributes.ARMOR_FLAT));
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
        HealthCalculator.forget(event.getEntity());
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
