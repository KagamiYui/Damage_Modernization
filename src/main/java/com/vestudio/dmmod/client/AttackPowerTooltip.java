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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 在武器 tooltip 上显示这把武器<b>增加的基础攻击力</b>。
 *
 * <h2>显示效果</h2>
 * <pre>
 *   +6 基础攻击力
 * </pre>
 * 数值是武器的攻击力修饰符本身（即它「增加」了多少），
 * <b>不是</b>含玩家自身基础值的总和。例如钻石剑会显示 {@code +6 基础攻击力}，
 * 含义是「装备后基础攻击力 +6」。
 *
 * <h2>为什么不改原版那一行</h2>
 * 原版仍会照常显示它自己的「攻击伤害」行（那是原版属性系统的真实内容）。
 * 本 mod 只是<b>额外增加一行</b>，说明这把武器折算成基础攻击力时增加多少，
 * 因此不会干扰其他模组或原版对 {@code attack_damage} 的读取与显示。
 *
 * <h2>配色</h2>
 * 沿用属性系统对「正向修饰符」的配色（蓝），与原版 {@code +N} 行的观感一致。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class AttackPowerTooltip {

    /** 「+N 名称」形式的翻译键，原版用于正向修饰符。 */
    private static final String PLUS_LINE_KEY = "attribute.modifier.plus.0";

    private AttackPowerTooltip() {
    }

    /**
     * 处理物品 tooltip，追加基础攻击力贡献行。
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
        double baseAttackPowerBonus = mainHandAttackDamage(stack);
        if (baseAttackPowerBonus == 0.0D) {
            return;
        }

        List<Component> lines = event.getToolTip();

        // 幂等保护：已经添加过同样内容时不重复添加。
        if (TooltipLines.anyContains(lines, DMAttributes.BASE_ATTACK_POWER.value().getDescriptionId())) {
            return;
        }

        lines.add(buildLine(baseAttackPowerBonus, event.getFlags()));
    }

    /**
     * 读取物品在主手上的攻击力修饰符数值。
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
     * 构造「+N 基础攻击力」行。
     *
     * <p>数值经由基础攻击力属性自身的显示逻辑渲染，
     * 配色沿用属性系统对正向修饰符的配色。
     *
     * @param value  增加的基础攻击力
     * @param flag   tooltip 标记
     * @return 该行的组件
     */
    private static Component buildLine(double value, TooltipFlag flag) {
        Holder<Attribute> attribute = DMAttributes.BASE_ATTACK_POWER;

        // 显示数值本身（传 null 运算类型），与属性面板保持一致。
        Component valueText = attribute.value().toValueComponent(null, value, flag);

        MutableComponent text = Component.translatable(PLUS_LINE_KEY,
                valueText,
                Component.translatable(attribute.value().getDescriptionId()));

        return text.withStyle(ChatFormatting.BLUE);
    }
}
