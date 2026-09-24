package com.vestudio.dmmod.util;

import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.TooltipFlag;

/**
 * 属性面板的数值排版逻辑。
 *
 * <p>刻意做成不依赖任何客户端类型的纯函数，
 * 因此服务端也能复用与测试。
 *
 * <h2>两种排版</h2>
 * <ul>
 *   <li>{@link #attackPowerValue}：有明确基础值的乘区（攻击力），
 *       排版为「结果（基础值 + 加成）」；</li>
 *   <li>{@link #bonusOnlyValue}：没有分开基础值的乘区，
 *       排版为「结果（增加量）」。</li>
 * </ul>
 *
 * <p>两者在<b>数值没有变动时都会省略括号</b>，只显示结果。
 */
public final class StatFormat {

    /** 浮点比较容差。 */
    private static final double EPSILON = 1.0E-6D;

    private StatFormat() {
    }

    /**
     * 「基础值 + 非基础值」型数值的排版：{@code 结果（基础值 + 非基础值）}。
     *
     * <p>用于拥有<b>可成长基础值</b>的体系（攻击力、生命值）：
     * 基础值本身可以变化（武器、装备），百分比与固定加成叠加在其上。
     *
     * <pre>
     *   结果 = 基础值 × (1 + 百分比) + 固定值
     *   非基础值 = 结果 − 基础值
     * </pre>
     *
     * <p>无非基础部分时只返回结果，不带括号。
     *
     * @param attribute 属性（决定数值的显示格式）
     * @param total     结果
     * @param base      基础值
     * @param bonus     非基础值（结果 − 基础值）
     * @return 排版后的文本
     */
    public static String basePlusBonus(Holder<Attribute> attribute,
                                       double total,
                                       double base,
                                       double bonus) {
        String text = render(attribute, total);
        if (isZero(bonus)) {
            return text;
        }
        return text + "（" + render(attribute, base)
                + " + " + render(attribute, Math.abs(bonus)) + "）";
    }

    /**
     * 攻击力区的数值文本：{@code 结果（基础攻击力 + 非基础攻击力）}。
     *
     * <p><b>基础攻击力包含武器贡献</b>：武器的攻击伤害会被换算并计入基础攻击力
     * （空手 1、钻石剑 7），因此这里传入的 {@code base} 是<b>手持当前武器时</b>
     * 的基础攻击力，而不是那个不含武器的 1。
     *
     * @param total 攻击力区结果
     * @param base  基础攻击力（含武器）
     * @param bonus 非基础攻击力（结果 − 基础攻击力）
     * @return 排版后的文本
     */
    public static String attackPowerValue(double total, double base, double bonus) {
        return basePlusBonus(DMAttributes.BASE_ATTACK_POWER, total, base, bonus);
    }

    /**
     * 无独立基础值的乘区：{@code 结果（增加量）}。
     *
     * <p>增加量以百分比呈现，且<b>不带 {@code +} 号</b>。
     * 数值没有变动时只返回结果，不带括号。
     *
     * @param attribute 属性（决定数值的显示格式）
     * @param total     结果
     * @param delta     增加量（结果 − 属性默认值）
     * @return 排版后的文本
     */
    public static String bonusOnlyValue(Holder<Attribute> attribute, double total, double delta) {
        String text = render(attribute, total);
        if (isZero(delta)) {
            return text;
        }
        return text + "（" + render(attribute, delta) + "）";
    }

    /**
     * 暴击率的实际数值：属性 + 外部来源。
     *
     * <p>判定暴击时用的就是这个和（见 {@code DamageEventHandler#onCriticalHit}），
     * 因此面板必须按同一口径显示，否则会出现「面板写着 5%、实际却一直在暴击」的错位。
     *
     * @param attributeValue 暴击率属性值
     * @param externalChance 外部来源提供的暴击率（例如星辉的 {@code critical_hit_chance}）
     * @return 实际暴击率
     */
    public static double critChanceTotal(double attributeValue, double externalChance) {
        return attributeValue + externalChance;
    }

    /**
     * 暴击区的实际倍率（攻击方视角，<b>不含</b>受害者的暴击伤害减免）。
     *
     * <p>与数据驱动的暴击区公式<b>同一口径</b>：
     * <pre>
     *   1 + (crit_damage + crit_damage_bonus + crit_bonus + 外部) − 1
     * </pre>
     * 即「倍率本体 + 加算子项 + 全局加成 + 外部来源」四项相加。
     *
     * <p>之所以把口径收在这里，是因为面板与伤害管线<b>必须一致</b>：
     * 任何一处漏算，玩家看到的数字就不再是实际生效的数字。
     *
     * @param critDamage      暴击伤害倍率本体（属性 {@code crit_damage}）
     * @param critDamageBonus 暴击伤害加成（属性 {@code crit_damage_bonus}）
     * @param globalBonus     配置里的暴击伤害全局加成
     * @param externalDamage  外部来源提供的暴击伤害加成（例如星辉的 {@code critical_hit_damage}）
     * @return 暴击区的实际倍率
     */
    public static double critDamageTotal(double critDamage,
                                         double critDamageBonus,
                                         double globalBonus,
                                         double externalDamage) {
        return critDamage + critDamageBonus + globalBonus + externalDamage;
    }

    /**
     * 从原版属性的总值反推「基础值」。
     *
     * <p>原版属性的总值由四部分构成：
     * <pre>
     *   总值 = 原版基础值 + 装备等加成 + 外部加成 + 本 mod 写回的差值
     * </pre>
     * 后两项都不属于基础值，反推时必须都减掉：
     * <ul>
     *   <li><b>外部加成</b>（如星辉的 perk）属于提升值，已经由 {@code *_flat}
     *       那边算过一份；</li>
     *   <li><b>本 mod 写回的差值</b>是「结果 − 原版总值」，不减就会把结果当成基础值。</li>
     * </ul>
     *
     * <p>抽成纯函数是为了让面板与测试走<b>同一份</b>算式——
     * 这条公式错一次就会让面板显示错一个数，不能各写一遍。
     *
     * @param vanillaTotal     原版属性的总值
     * @param externalAdditive 外部加成的加算总量
     * @param ourComputedDelta 本 mod 写回的差值；没有则传 0
     * @return 反推出的基础值（不小于 0）
     */
    public static double deriveBase(double vanillaTotal,
                                    double externalAdditive,
                                    double ourComputedDelta) {
        if (!Double.isFinite(vanillaTotal)) {
            return 0.0D;
        }
        return Math.max(0.0D, vanillaTotal - externalAdditive - ourComputedDelta);
    }

    /**
     * 调用属性自身的显示逻辑渲染一个数值。
     *
     * <p>传 {@code null} 运算类型，表示「显示数值本身」而非修饰符。
     *
     * @param attribute 属性
     * @param value     数值
     * @return 渲染后的字符串
     */
    public static String render(Holder<Attribute> attribute, double value) {
        return attribute.value()
                .toValueComponent(null, value, TooltipFlag.NORMAL)
                .getString();
    }

    /**
     * {@return 数值是否可视为零}
     *
     * @param value 数值
     */
    public static boolean isZero(double value) {
        return Math.abs(value) <= EPSILON;
    }
}
