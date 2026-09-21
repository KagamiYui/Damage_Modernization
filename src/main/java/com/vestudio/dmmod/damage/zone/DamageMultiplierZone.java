package com.vestudio.dmmod.damage.zone;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.api.zone.IDamageZone;
import com.vestudio.dmmod.api.zone.ZonePriorities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 内置乘区：伤害倍率（乘算区）。
 *
 * <p>公式：
 * <pre>
 *   伤害倍率区 = 属性倍率 × 全局系数
 * </pre>
 *
 * <p>这是一个纯粹的<b>乘法</b>乘区：每个来源都提供独立的乘数，
 * 彼此连乘。与 {@link DamageAmplifierZone} 的加算区形成互补，
 * 让数值设计者有「加法」和「乘法」两种成长手段。
 *
 * <p>例如：属性给 ×1.2，另一个 mod 再给 ×1.3，
 * 最终该乘区为 1.56，而不是 1.5。
 */
public final class DamageMultiplierZone implements IDamageZone {

    /** 乘区标识。 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "damage_multiplier");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return ZonePriorities.DAMAGE_MULTIPLIER;
    }

    @Override
    public void apply(DamageContext context) {
        if (!Config.ENABLE_MULTIPLIER_ZONE.getAsBoolean()) {
            return;
        }

        LivingEntity attacker = context.attacker();

        // 属性倍率：默认 1.0，因此不配置时该乘区不影响伤害。
        // 使用乘法叠加，让多个来源真正独立。
        if (attacker != null) {
            context.multiplyDamageMultiplier(
                    attacker.getAttributeValue(DMAttributes.DAMAGE_MULTIPLIER));
        }

        // 全局倍率系数。
        context.multiplyDamageMultiplier(Config.DAMAGE_MULTIPLIER_ZONE_FACTOR.get());
    }
}
