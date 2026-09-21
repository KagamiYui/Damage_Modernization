package com.vestudio.dmmod.damage.zone;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.api.zone.IDamageZone;
import com.vestudio.dmmod.api.zone.ZonePriorities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 内置乘区：伤害提升（加算区）。
 *
 * <p>公式：
 * <pre>
 *   伤害提升区 = (1 + Σ伤害提升) × 全局系数
 * </pre>
 *
 * <p>这是「加算区」：同一乘区内来自不同来源的加成彼此<b>相加</b>，
 * 而不是相乘。这样设计是为了避免多个小额加成相乘后爆炸，
 * 也让数值成长更可预测。
 *
 * <p>与 {@link DamageMultiplierZone} 的区别：本乘区内部是加法，
 * 那个乘区是纯乘法。想要「独立乘算」的效果请用后者。
 */
public final class DamageAmplifierZone implements IDamageZone {

    /** 乘区标识。 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "damage_amplifier");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return ZonePriorities.DAMAGE_AMPLIFIER;
    }

    @Override
    public void apply(DamageContext context) {
        if (!Config.ENABLE_AMPLIFIER_ZONE.getAsBoolean()) {
            return;
        }

        LivingEntity attacker = context.attacker();

        // 伤害提升来自攻击者属性。其他 mod 若已通过事件改过上下文，
        // 这里的 addDamageAmplifier 会与其累加，符合「加算区」语义。
        if (attacker != null) {
            context.addDamageAmplifier(
                    attacker.getAttributeValue(DMAttributes.DAMAGE_AMPLIFIER));
        }

        // 全局加成：作用于整个加算区，便于整体调参。
        context.addDamageAmplifier(Config.DAMAGE_AMPLIFIER_ZONE_BONUS.get());
    }
}
