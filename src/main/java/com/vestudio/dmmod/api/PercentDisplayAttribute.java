package com.vestudio.dmmod.api;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;

/**
 * 以百分比形式显示的取值范围属性。
 *
 * <h2>为什么需要这个类</h2>
 * 本 mod 的所有比例类数值在<b>代码与配置中以小数存储</b>（0.05、1.5），
 * 这样数学运算直接、不会出现「除以 100」这类散落各处的换算错误。
 * 但玩家不应该看到 {@code 0.05} 这种反直觉的数字，而应看到 {@code 5%}。
 *
 * <p>因此本类只负责<b>显示层</b>的换算，存储层保持小数不变。
 *
 * <h2>scaleFactor 的含义</h2>
 * 显示值 = 存储值 × scaleFactor。根据语义有两种取值：
 * <ul>
 *   <li><b>100</b>：存储值本身就是「比例」。适用于暴击率、伤害提升等，
 *       {@code 0.05 → 5%}、{@code 0.25 → 25%}。</li>
 *   <li><b>100 且存储值为倍率</b>：适用于暴击伤害、伤害倍率等，
 *       {@code 1.5 → 150%}、{@code 2.0 → 200%}。</li>
 * </ul>
 *
 * <p>注意修饰符（+X / -X）的显示遵循原版约定：
 * {@code ADD_VALUE} 等加法运算显示为固定值，其余运算按百分比显示。
 * 这与 NeoForge 的 {@code PercentageAttribute} 行为一致，
 * 因此装备词条的 tooltip 不会出现语义错乱。
 */
public class PercentDisplayAttribute extends RangedAttribute {

    /** 与 NeoForge 保持一致的数字格式：#.## 且不受系统区域设置影响。 */
    private static final DecimalFormat FORMAT =
            new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /** 保留倍数，用于把存储值换算成显示用的百分数。 */
    private final double scaleFactor;

    /**
     * 构造一个百分比显示的属性。
     *
     * @param descriptionId 语言键
     * @param defaultValue  默认值（小数形式）
     * @param min           最小值
     * @param max           最大值
     */
    public PercentDisplayAttribute(String descriptionId, double defaultValue, double min, double max) {
        this(descriptionId, defaultValue, min, max, 100.0D);
    }

    /**
     * 构造一个百分比显示的属性，并指定显示倍数。
     *
     * @param descriptionId 语言键
     * @param defaultValue  默认值（小数形式）
     * @param min           最小值
     * @param max           最大值
     * @param scaleFactor   显示倍数，通常为 100
     */
    public PercentDisplayAttribute(String descriptionId, double defaultValue,
                                   double min, double max, double scaleFactor) {
        super(descriptionId, defaultValue, min, max);
        this.scaleFactor = scaleFactor;
    }

    /**
     * 把属性值或修饰符数值格式化为显示组件。
     *
     * <p>{@code op} 为 {@code null} 时表示正在显示属性<b>本身</b>的数值
     * （例如 tooltip 里那一行「暴击率 5%」），这正是本类要改成百分比的场景。
     *
     * <p>修饰符则遵循原版约定：加法类运算显示为固定值，其余按百分比显示。
     *
     * @param op    运算类型；显示属性本身时为 null
     * @param value 待显示的数值
     * @param flag  tooltip 标记
     * @return 格式化后的组件
     */
    @Override
    public MutableComponent toValueComponent(@Nullable Operation op, double value, TooltipFlag flag) {
        if (op == null || op == Operation.ADD_VALUE) {
            // 显示属性本身或加法修饰符：按百分比呈现。
            return Component.translatable("damagemodernization.value.percent",
                    FORMAT.format(value * this.scaleFactor));
        }

        // 乘法类修饰符：沿用原版百分比表示。
        return Component.translatable("neoforge.value.percent", FORMAT.format(value * 100.0D));
    }
}
