package com.vestudio.dmmod;

import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.damage.BaseAttackPowerConverter;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 临时诊断类：验证「武器提升的是基础攻击力」。
 *
 * <p>做法：把真实武器的属性修饰符（与原版装备逻辑一致）装到假玩家身上，
 * 再调用真实的转换逻辑，对比 {@code attack_damage} 与 {@code base_attack_power}。
 *
 * <p>本类仅用于开发期验证，验证完成后会删除。
 */
@EventBusSubscriber(modid = DamageModernization.MODID)
public final class TempWeaponDiagnostic {

    private TempWeaponDiagnostic() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            FakePlayer player = FakePlayerFactory.getMinecraft(event.getServer().overworld());

            runCase(player, "空手(无修饰符)", null);
            runCase(player, "钻石剑", new ItemStack(Items.DIAMOND_SWORD));
            runCase(player, "下界合金剑", new ItemStack(Items.NETHERITE_SWORD));

            FakePlayerFactory.unloadLevel(event.getServer().overworld());
        } catch (Throwable t) {
            DamageModernization.LOGGER.error("[DIAG] failed", t);
        }
    }

    /**
     * 执行一次对比：装上武器的攻击力修饰符 → 调用转换 → 输出结果。
     *
     * @param player 假玩家
     * @param label  用例名
     * @param weapon 武器；null 表示空手
     */
    private static void runCase(FakePlayer player, String label, ItemStack weapon) {
        AttributeInstance vanilla = player.getAttribute(Attributes.ATTACK_DAMAGE);
        AttributeInstance base = player.getAttribute(DMAttributes.BASE_ATTACK_POWER);
        if (vanilla == null || base == null) {
            DamageModernization.LOGGER.warn("[DIAG] {} skipped: attribute missing", label);
            return;
        }

        // 清空上一轮的修饰符，模拟换装。
        vanilla.removeModifiers();
        base.removeModifiers();

        // 把武器真实的主手攻击力修饰符装到原版攻击伤害上，
        // 这与原版装备结算的行为一致。
        if (weapon != null && !weapon.isEmpty()) {
            ItemAttributeModifiers modifiers = weapon.getAttributeModifiers();
            for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                if (!entry.slot().test(EquipmentSlot.MAINHAND)) {
                    continue;
                }
                if (!entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
                    continue;
                }
                AttributeModifier m = entry.modifier();
                vanilla.addOrUpdateTransientModifier(
                        new AttributeModifier(m.id(), m.amount(), m.operation()));
            }
        }

        double vanillaBefore = vanilla.getValue();
        double baseBefore = base.getValue();

        // ---- 调用真实转换逻辑 ----
        double resolved = BaseAttackPowerConverter.resolveBaseAttackPower(player);

        double vanillaAfter = vanilla.getValue();
        double baseAfter = base.getValue();

        StringBuilder applied = new StringBuilder();
        for (AttributeModifier m : base.getModifiers()) {
            applied.append('[').append(m.id().getPath())
                    .append(" amount=").append(m.amount())
                    .append(" op=").append(m.operation()).append(']');
        }

        DamageModernization.LOGGER.info(
                "[DIAG] === {} ===\n"
                        + "  attack_damage       : {} -> {} (mods={})\n"
                        + "  base_attack_power   : {} -> {} (mods={})\n"
                        + "  converter returned  : {}\n"
                        + "  mirrored modifiers  : {}\n"
                        + "  base==vanilla?      : {}",
                label,
                vanillaBefore, vanillaAfter, vanilla.getModifiers().size(),
                baseBefore, baseAfter, base.getModifiers().size(),
                resolved,
                applied,
                Math.abs(baseAfter - vanillaAfter) < 1.0E-6D);
    }
}
