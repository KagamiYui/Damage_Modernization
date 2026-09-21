package com.vestudio.dmmod;

import com.vestudio.dmmod.util.TooltipLines;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 临时诊断：验证 tooltip 行的定位与数值解析。
 *
 * <p>验证完成后删除。
 */
@EventBusSubscriber(modid = DamageModernization.MODID)
public final class TempTipParse {

    private static final String BASE_KEY = "attribute.modifier.equals.0";

    private TempTipParse() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 复刻原版属性行的结构：literal(" ").append(translatable(...))
        MutableComponent line = Component.literal(" ").append(
                Component.translatable(BASE_KEY,
                        Component.literal("7"),
                        Component.translatable("attribute.name.generic.attack_damage")))
                .withStyle(ChatFormatting.DARK_GREEN);

        DamageModernization.LOGGER.info("[TIP] 定位 -> {} (期望 true)",
                TooltipLines.containsKey(line, BASE_KEY));
        DamageModernization.LOGGER.info("[TIP] 文本 -> '{}'", line.getString());

        double v = TooltipLines.parseValue(line);
        DamageModernization.LOGGER.info("[TIP] 解析数值 -> {} (期望 7.0) -> {}",
                v, Math.abs(v - 7.0D) < 1.0E-6D ? "PASS" : "FAIL");

        check("整数", " 12 Attack Damage", 12.0D);
        check("小数", " 6.5 Attack Damage", 6.5D);
        check("负数", " -2 Attack Damage", -2.0D);
        check("无空格", "4 Attack Damage", 4.0D);

        MutableComponent other = Component.literal("Unrelated text");
        double nan = TooltipLines.parseValue(other);
        DamageModernization.LOGGER.info("[TIP] 非数值行 -> {} -> {}",
                nan, Double.isNaN(nan) ? "PASS" : "FAIL");
    }

    private static void check(String label, String text, double expect) {
        Component c = Component.literal(text);
        double v = TooltipLines.parseValue(c);
        DamageModernization.LOGGER.info("[TIP] {} '{}' -> {} (期望 {}) -> {}",
                label, text, v, expect, Math.abs(v - expect) < 1.0E-6D ? "PASS" : "FAIL");
    }
}
