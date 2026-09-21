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
 * <p>其中「基础攻击力」由 {@code BaseAttackPowerConverter} 从原版攻击伤害转换而来，
 * 因此上下文里携带的值已经是正确的基准。
 *
 * <p>配置项 {@code attackPowerFormula} 允许模组包在不写代码的情况下切换形态：
 * <ul>
 *   <li>{@code FULL}：完整形态（默认），百分比与固定加值都计入；</li>
 *   <li>{@code FLAT_ONLY}：忽略百分比，只看基础值与固定加值；</li>
 *   <li>{@code PERCENT_ONLY}：只看基础值与百分比，忽略固定加值。</li>
 * </ul>
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
            String formula = Config.ATTACK_POWER_FORMULA.get();

            if (!"FLAT_ONLY".equals(formula)) {
                context.addAttackPowerPercent(
                        attacker.getAttributeValue(DMAttributes.ATTACK_POWER_PERCENT));
            }
            if (!"PERCENT_ONLY".equals(formula)) {
                context.addAttackPowerFlat(
                        attacker.getAttributeValue(DMAttributes.ATTACK_POWER_FLAT));
            }
        }

        // 注意：全局缩放系数由 DamagePipeline 统一作用于「攻击力区」的最终结果，
        // 而不是在这里只缩放基础攻击力——否则固定加值会被漏掉，
        // 导致 scale 的语义变成「部分缩放」。
    }
}
