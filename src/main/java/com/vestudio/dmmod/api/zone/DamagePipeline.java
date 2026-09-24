package com.vestudio.dmmod.api.zone;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 伤害合成管线：对外暴露的入口，负责按顺序执行所有乘区并算出最终伤害。
 *
 * <p>其他 mod 一般不需要直接调用本类；它由本 mod 的事件处理器驱动。
 * 但本类提供了 {@link #compose(DamageContext)} 以便：
 * <ul>
 *   <li>其他 mod 在不引起真实伤害的情况下「预演」一次伤害计算（例如显示预估伤害）；</li>
 *   <li>测试代码构造上下文并验证乘区行为。</li>
 * </ul>
 */
public final class DamagePipeline {

    private DamagePipeline() {
    }

    /**
     * 依序执行所有乘区，产出最终伤害。
     *
     * <p>流程：
     * <ol>
     *   <li>触发 {@link DamageZoneEvent}，允许监听器即时增删乘区、直接改写上下文；</li>
     *   <li>按优先级依次执行注册表中的乘区；</li>
     *   <li>若期间有乘区指定了最终伤害，则直接采用；</li>
     *   <li>否则用四乘区公式合成。</li>
     * </ol>
     *
     * <p>任何乘区抛出异常都会被捕获并记录，<b>不会</b>中断整条管线——
     * 一个第三方 mod 的 bug 不应该让所有伤害归零。
     *
     * @param context 伤害上下文
     * @return 最终伤害（非负、有限）
     */
    public static double compose(DamageContext context) {
        if (context.isProcessed()) {
            // 防止同一上下文被重复结算，导致加成叠加。
            return computeZones(context);
        }

        // 允许其他 mod 在结算前动态调整上下文。
        // 事件异常同样被隔离，避免破坏伤害流程。
        try {
            NeoForge.EVENT_BUS.post(new DamageZoneEvent(context));
        } catch (Exception e) {
            DamageModernization.LOGGER.error("DamageZoneEvent listener threw an exception", e);
        }

        for (IDamageZone zone : DamageZoneRegistry.getZones()) {
            applySafely(zone, context);
        }

        context.markProcessed();

        if (context.hasFinalDamageOverride()) {
            Double override = context.finalDamageOverride();
            return override == null ? computeZones(context) : override;
        }

        return computeZones(context);
    }

    /**
     * 执行单个乘区，隔离其异常。
     *
     * @param zone    乘区
     * @param context 上下文
     */
    private static void applySafely(IDamageZone zone, DamageContext context) {
        try {
            zone.apply(context);
        } catch (Exception e) {
            DamageModernization.LOGGER.error(
                    "Damage zone {} threw an exception and was skipped for this hit",
                    zone.id(), e);
        }
    }

    /**
     * 四乘区公式本体。
     *
     * <h2>数据驱动优先</h2>
     * 若数据文件提供了完整的伤害乘区定义，则<b>由公式求值</b>；
     * 否则退回下面内置的硬编码公式。
     * 这条退路保证数据文件缺失或写坏时，mod 依然能正常工作。
     *
     * <p>注意求值时读取的是 {@link DamageContext} 中<b>已被各乘区修改过</b>的值，
     * 因此公式代表的是「所有乘区贡献之和」，而不是固定的原版数值。
     *
     * @param ctx 上下文
     * @return 最终伤害
     */
    public static double computeZones(DamageContext ctx) {
        // 优先走数据驱动的公式。
        double damage;
        if (com.vestudio.dmmod.damage.ZoneIds.damageZonesPresent()) {
            damage = com.vestudio.dmmod.damage.DamageFormulaEvaluator.evaluate(ctx);
        } else {
            damage = computeZonesLegacy(ctx);
        }

        // 承伤公式（防守方）。
        // 它只作用在伤害结果之上，不会回头改写攻击方的任何乘区，
        // 因此攻守双方保持独立。
        double taken = com.vestudio.dmmod.damage.TakenFormulaEvaluator.evaluate(ctx, damage);

        double finalDamage = damage * taken;

        if (!Double.isFinite(finalDamage) || finalDamage < 0.0D) {
            return 0.0D;
        }
        return finalDamage;
    }

    /**
     * 内置的硬编码公式，作为数据缺失时的退路。
     *
     * <h2>不含增减伤</h2>
     * 「增伤与减伤」已合并为一个加算区并移入承伤侧
     * （只有那里能同时读到攻守双方的贡献），因此这里<b>不再</b>乘增伤区，
     * 否则会与承伤侧重复计算。
     *
     * @param ctx 上下文
     * @return 最终伤害
     */
    private static double computeZonesLegacy(DamageContext ctx) {
        double attackPowerZone = ctx.baseAttackPower() * (1.0D + ctx.attackPowerPercent())
                + ctx.attackPowerFlat();

        double multiplierZone = ctx.damageMultiplier();

        // 暴击区：不暴击时为 1.0，保证不影响伤害。
        //
        // 暴击伤害的加成允许为负（减益效果），但乘区内有下限：
        // 生效值不低于 1.0。这样「暴击」永远不会比不暴击造成更低的伤害，
        // 即便暴击伤害属性被削减到 1.0 以下。
        //
        // 受害者的「暴击伤害减免」在此<b>直接削减系数</b>：
        // 只削减暴击增益（倍率−1）那部分，因此不会动到非暴击伤害。
        double critZone = 1.0D;
        if (ctx.isCritical()) {
            // 倍率本体 + 加算子项（与数据驱动公式保持一致的语义）。
            double critMultiplier = ctx.critDamage();
            LivingEntity attacker = ctx.attacker();
            if (attacker != null) {
                var bonusAttr = attacker.getAttribute(DMAttributes.CRIT_DAMAGE_BONUS);
                if (bonusAttr != null) {
                    critMultiplier += bonusAttr.getValue();
                }
            }

            // 外部来源提供的暴伤加成（例如星辉的 perk），同样按加算子项并入。
            critMultiplier += ctx.externalCritDamageBonus();

            LivingEntity victim = ctx.victim();
            if (victim != null) {
                var reductionAttr = victim.getAttribute(DMAttributes.CRIT_DAMAGE_TAKEN_REDUCTION);
                double reduction = reductionAttr == null ? 0.0D : reductionAttr.getValue();
                critMultiplier = 1.0D + (critMultiplier - 1.0D) * (1.0D - reduction);
            }
            critZone = Math.max(1.0D, critMultiplier);
        }
        double result = attackPowerZone * multiplierZone * critZone;

        // 防御性收尾：非有限值或负数一律归零。
        if (!Double.isFinite(result) || result < 0.0D) {
            return 0.0D;
        }

        return result;
    }

    /**
     * 构造一次「生物攻击」的伤害上下文。
     *
     * <p>公开该方法是为了让本 mod 的事件处理器与外部工具都能
     * 以统一方式创建上下文，而不必重复抄写属性读取逻辑。
     *
     * @param source          伤害来源
     * @param attacker        攻击者
     * @param victim          受害实体
     * @param baseAttackPower 基础攻击力
     * @param crit            是否暴击
     * @return 新建的上下文（尚未结算）
     */
    public static DamageContext createMeleeContext(DamageSource source,
                                                   LivingEntity attacker,
                                                   LivingEntity victim,
                                                   double baseAttackPower,
                                                   boolean crit) {
        return new DamageContext(
                source, attacker, victim, true, false,
                baseAttackPower,
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.ATTACK_POWER_PERCENT),
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.ATTACK_POWER_FLAT),
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_AMPLIFIER),
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_MULTIPLIER),
                crit,
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.CRIT_DAMAGE));
    }

    /**
     * 构造一次「环境伤害」的伤害上下文。
     *
     * @param source    伤害来源
     * @param victim    受害实体
     * @param rawDamage 原始伤害，直接充当攻击力区
     * @return 新建的上下文（尚未结算）
     */
    public static DamageContext createEnvironmentalContext(DamageSource source,
                                                           LivingEntity victim,
                                                           double rawDamage) {
        return new DamageContext(
                source, null, victim, false, true,
                rawDamage, 0.0D, 0.0D,
                victim.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_AMPLIFIER),
                victim.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_MULTIPLIER),
                false,
                victim.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.CRIT_DAMAGE));
    }

    /**
     * 便捷方法：为一次生物攻击构造上下文并直接算出伤害。
     *
     * @param source    伤害来源
     * @param attacker  攻击者
     * @param victim    受害实体
     * @param baseAttackPower 基础攻击力
     * @param crit      是否暴击
     * @return 最终伤害
     */
    public static double composeMelee(DamageSource source,
                                      LivingEntity attacker,
                                      LivingEntity victim,
                                      double baseAttackPower,
                                      boolean crit) {
        DamageContext ctx = new DamageContext(
                source, attacker, victim, true, false,
                baseAttackPower,
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.ATTACK_POWER_PERCENT),
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.ATTACK_POWER_FLAT),
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_AMPLIFIER),
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_MULTIPLIER),
                crit,
                attacker.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.CRIT_DAMAGE));
        return compose(ctx);
    }

    /**
     * 便捷方法：为一次环境伤害构造上下文并直接算出伤害。
     *
     * @param source    伤害来源
     * @param victim    受害实体
     * @param rawDamage 原始伤害，直接作为攻击力区
     * @return 最终伤害
     */
    public static double composeEnvironmental(DamageSource source,
                                              LivingEntity victim,
                                              double rawDamage) {
        DamageContext ctx = new DamageContext(
                source, null, victim, false, true,
                rawDamage, 0.0D, 0.0D,
                victim.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_AMPLIFIER),
                victim.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.DAMAGE_MULTIPLIER),
                false,
                victim.getAttributeValue(com.vestudio.dmmod.api.DMAttributes.CRIT_DAMAGE));
        return compose(ctx);
    }

    /**
     * 读取配置中某个乘区的启用状态。
     *
     * <p>供内置乘区在 {@code apply} 中自查，避免关闭后仍生效。
     *
     * @param getter 配置读取器
     * @return 是否启用
     */
    public static boolean isEnabled(@Nullable java.util.function.BooleanSupplier getter) {
        return getter != null && getter.getAsBoolean();
    }

    /**
     * {@return 全局四乘区总开关是否开启}
     */
    public static boolean isModelEnabled() {
        return Config.ENABLE_FOUR_ZONE_MODEL.getAsBoolean();
    }

    /**
     * 便于外部以 lambda 形式注册临时乘区。
     *
     * @param id       乘区标识
     * @param priority 优先级
     * @param action   乘区逻辑
     * @return 被注册的乘区实例
     */
    public static IDamageZone registerSimple(net.minecraft.resources.ResourceLocation id,
                                             int priority,
                                             Consumer<DamageContext> action) {
        IDamageZone zone = new IDamageZone() {
            @Override
            public net.minecraft.resources.ResourceLocation id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public void apply(DamageContext context) {
                action.accept(context);
            }
        };
        DamageZoneRegistry.register(zone);
        return zone;
    }
}
