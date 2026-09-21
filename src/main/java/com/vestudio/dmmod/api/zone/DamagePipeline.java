package com.vestudio.dmmod.api.zone;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;

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
     * <p>注意这里读取的是 {@link DamageContext} 中<b>已被各乘区修改过</b>的值，
     * 因此公式代表的是「所有乘区贡献之和」，而不是固定的原版数值。
     *
     * @param ctx 上下文
     * @return 最终伤害
     */
    public static double computeZones(DamageContext ctx) {
        double attackPowerZone = ctx.baseAttackPower() * (1.0D + ctx.attackPowerPercent())
                + ctx.attackPowerFlat();

        // 攻击力区的全局缩放：作用于整个乘区（含固定加值），
        // 让模组包可以用一个系数整体放大或压缩伤害区间。
        attackPowerZone *= Config.ATTACK_POWER_ZONE_SCALE.get();

        double amplifierZone = 1.0D + ctx.damageAmplifier();

        double multiplierZone = ctx.damageMultiplier();

        double critZone = ctx.isCritical() ? ctx.critDamage() : 1.0D;

        double result = attackPowerZone * amplifierZone * multiplierZone * critZone;

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
