package com.vestudio.dmmod.client;

import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.util.TooltipLines;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 把武器 tooltip 上原版的「攻击伤害」行，就地替换为「基础攻击力」。
 *
 * <h2>显示效果</h2>
 * <pre>
 *   +6 基础攻击力
 * </pre>
 * 显示的是这把武器<b>增加</b>的基础攻击力，也就是武器自身的攻击力修饰符数值，
 * <b>不包含</b>玩家自身的基础值。例如钻石剑显示 {@code +6}，
 * 含义是「装备后基础攻击力 +6」，而不是装备后的总和（那会是 7）。
 *
 * <h2>替换而非追加</h2>
 * 本 mod 已把该数值的语义重写为基础攻击力，继续显示「攻击伤害」会与实际语义不符，
 * 因此直接<b>占据原行的位置</b>，避免同一个数值出现两行。
 *
 * <h2>颜色</h2>
 * 与其它属性行保持一致，使用原版属性行的深绿（{@link ChatFormatting#DARK_GREEN}）。
 *
 * <h2>定位方式</h2>
 * 原版该行由 {@code attribute.modifier.equals.0} 生成，
 * 且结构为 {@code literal(" ").append(translatable)}，
 * 可翻译内容位于<b>兄弟节点</b>而非顶层，因此定位时必须递归查找。
 * 找不到时<b>不做任何改动</b>，避免误删其他模组的内容。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class AttackPowerTooltip {

    /** 原版「基础数值」行的翻译键，用于定位待替换的行。 */
    private static final String BASE_LINE_KEY = "attribute.modifier.equals.0";

    /** 「+N 名称」形式的翻译键，用于表达「增加」的语义。 */
    private static final String PLUS_LINE_KEY = "attribute.modifier.plus.0";

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

        // 该武器增加的基础攻击力 = 它在主手上的攻击力修饰符数值。
        double bonus = mainHandAttackDamage(stack);
        if (bonus == 0.0D) {
            return;
        }

        List<Component> lines = event.getToolTip();

        int index = indexOfVanillaBaseLine(lines);
        if (index < 0) {
            return;
        }

        lines.set(index, buildLine(bonus, event.getFlags()));
    }

    /**
     * 读取物品在主手上的攻击力修饰符数值。
     *
     * <p>这是武器<b>增加</b>的攻击力，不含玩家自身的基础值。
     *
     * @param stack 物品
     * @return 修饰符数值；没有则返回 0
     */
    private static double mainHandAttackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getAttributeModifiers();
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND)
                    && entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
                return entry.modifier().amount();
            }
        }
        return 0.0D;
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
     * 构造「+N 基础攻击力」行。
     *
     * <p>结构与原版一致（前导空格 + 数值 + 属性名），颜色沿用原版属性行的深绿。
     *
     * @param value 增加的基础攻击力
     * @param flag  tooltip 标记
     * @return 该行的组件
     */
    private static Component buildLine(double value, TooltipFlag flag) {
        Holder<Attribute> attribute = DMAttributes.BASE_ATTACK_POWER;

        // 传 null 表示显示数值本身，与属性面板保持一致。
        Component valueText = attribute.value().toValueComponent(null, value, flag);

        // plus 键自带 "+" 前缀，因此这里表示的是「增加多少」。
        MutableComponent text = Component.translatable(PLUS_LINE_KEY,
                valueText,
                Component.translatable(attribute.value().getDescriptionId()));

        return Component.literal(" ").append(text).withStyle(ChatFormatting.DARK_GREEN);
    }
}
