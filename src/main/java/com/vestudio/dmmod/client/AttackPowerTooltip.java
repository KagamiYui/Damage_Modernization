package com.vestudio.dmmod.client;

import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.util.TooltipLines;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 把武器 tooltip 上原版的「攻击伤害」行，就地替换为「基础攻击力」。
 *
 * <h2>显示效果</h2>
 * <pre>
 *   基础攻击力   7
 * </pre>
 * 数值沿用原版那一行的数值（{@code 武器修饰符 + 玩家自身基础值}），
 * 因此<b>数字与改动前完全相同</b>，只是属性名称变成了基础攻击力。
 *
 * <h2>替换而非追加</h2>
 * 本 mod 已把该数值的语义重写为基础攻击力，继续显示「攻击伤害」会与实际语义不符，
 * 因此直接<b>占据原行的位置</b>，避免同一个数值出现两行。
 *
 * <h2>颜色</h2>
 * 与其它属性行保持一致，使用原版的深绿（{@link ChatFormatting#DARK_GREEN}）。
 *
 * <h2>定位方式</h2>
 * 原版该行由 {@code attribute.modifier.equals.0} 生成，
 * 且结构为 {@code literal(" ").append(translatable)}，
 * 可翻译内容位于<b>兄弟节点</b>而非顶层，因此定位时必须递归查找。
 * 找不到时<b>不做任何改动</b>，避免误删其他模组的内容。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class AttackPowerTooltip {

    /** 原版「基础数值」行的翻译键。 */
    private static final String BASE_LINE_KEY = "attribute.modifier.equals.0";

    private AttackPowerTooltip() {
    }

    /**
     * 处理物品 tooltip，就地替换攻击伤害行。
     *
     * @param event tooltip 事件，其列表可修改
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }

        List<Component> lines = event.getToolTip();

        // 定位原版「攻击伤害」那一行。
        int index = indexOfVanillaBaseLine(lines);
        if (index < 0) {
            return;
        }

        Component vanillaLine = lines.get(index);

        // 沿用原行的数值，保证数字不变。
        double value = TooltipLines.parseValue(vanillaLine);
        if (Double.isNaN(value)) {
            // 解析不出数值时保持原样，避免显示成空白。
            return;
        }

        lines.set(index, buildLine(value, event.getFlags()));
    }

    /**
     * 找出原版「基础数值」行的下标。
     *
     * @param lines tooltip 行列表
     * @return 下标，或 -1
     */
    private static int indexOfVanillaBaseLine(List<Component> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (TooltipLines.containsKey(lines.get(i), BASE_LINE_KEY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 构造「基础攻击力」行。
     *
     * <p>结构与原版一致（前导空格 + 数值 + 属性名），
     * 颜色沿用原版的深绿，使观感与其余属性行统一。
     *
     * @param value 显示数值
     * @param flag  tooltip 标记
     * @return 该行的组件
     */
    private static Component buildLine(double value, TooltipFlag flag) {
        Holder<Attribute> attribute = DMAttributes.BASE_ATTACK_POWER;

        // 传 null 表示显示数值本身，与属性面板保持一致。
        Component valueText = attribute.value().toValueComponent(null, value, flag);

        MutableComponent text = Component.translatable(BASE_LINE_KEY,
                valueText,
                Component.translatable(attribute.value().getDescriptionId()));

        return Component.literal(" ").append(text).withStyle(ChatFormatting.DARK_GREEN);
    }
}
