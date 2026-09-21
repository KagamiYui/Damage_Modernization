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
