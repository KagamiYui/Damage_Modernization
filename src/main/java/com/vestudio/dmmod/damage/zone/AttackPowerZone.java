package com.vestudio.dmmod.damage.zone;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.api.zone.IDamageZone;
import com.vestudio.dmmod.api.zone.ZonePriorities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 内置乘区：攻击力区。
 *
 * <p>公式：
 * <pre>
 *   攻击力区 = 基础攻击力 × (1 + 百分比提升) + 固定加值
 * </pre>
 *
 * <p>「基础攻击力」由 {@code BaseAttackPowerConverter} 从原版攻击伤害转换而来，
 * 因此上下文里携带的值已经是正确的基准。
 *
 * <p>百分比与固定加值从攻击者的属性读取后累加进上下文。
 * 注意数据驱动的伤害公式是<b>直接读实体属性</b>的，不读这里的上下文字段，
 * 因此本乘区在正常路径下只是把信息补齐，供兜底公式与第三方读取。
 */
public final class AttackPowerZone implements IDamageZone {

    /** 乘区标识。 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "attack_power");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return ZonePriorities.ATTACK_POWER;
    }

    @Override
    public void apply(DamageContext context) {
        if (!Config.ENABLE_ATTACK_POWER_ZONE.getAsBoolean()) {
            return;
        }

        LivingEntity attacker = context.attacker();

        // 攻击力百分比与固定加值来自攻击者的属性。
        // 环境伤害没有攻击者，此时保持上下文中的原始数值不变。
        if (attacker != null) {
            context.addAttackPowerPercent(
                    attacker.getAttributeValue(DMAttributes.ATTACK_POWER_PERCENT));
            context.addAttackPowerFlat(
                    attacker.getAttributeValue(DMAttributes.ATTACK_POWER_FLAT));
        }
    }
}
