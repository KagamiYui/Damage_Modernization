package com.vestudio.dmmod.api;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 伤害现代化（Damage Modernization）的全部属性定义。
 *
 * <p>四乘区模型：
 * <pre>
 *   最终伤害 = 攻击力区 × 伤害提升区 × 伤害倍率区 × 暴击伤害区
 * </pre>
 *
 * <p>其中「攻击力区」本身是一次独立运算：
 * <pre>
 *   攻击力区 = 基础攻击力 × (1 + 攻击力百分比提升) + 固定攻击力
 * </pre>
 *
 * <h2>关于「基础攻击力」</h2>
 * 原版的 {@code generic.attack_damage} 语义是「最终伤害值」，直接参与扣血。
 * 本 mod 把它重写为「基础攻击力」：它是一个<b>基准数值</b>，本身不再等同于最终伤害，
 * 而是要经过四乘区运算后才成为伤害。后续所有「百分比提升攻击力」都以这个数值为基准。
 *
 * <p>为了保持原版手感，基础攻击力的默认值与原版攻击伤害一致：
 * 空手 = 1.0，石剑 = 4.0，钻石剑 = 6.0，下界合金剑 = 7.0。
 *
 * @see com.vestudio.dmmod.damage.DamageZones 四乘区的具体运算
 */
public final class DMAttributes {

    /** 独立的属性注册表，命名空间为 mod id。 */
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, DamageModernization.MODID);

    /**
     * 基础攻击力：一切攻击力计算的基准。
     *
     * <p>默认 1.0（对应原版空手攻击力）。
     * 武器通过 AttributeModifier 在该值之上叠加，从而保持「空手 1 + 剑 3 = 4」这样的原版数值关系。
     *
     * <p>注意：这不是最终伤害。真正的伤害要经过四乘区运算。
     */
    public static final Holder<Attribute> BASE_ATTACK_POWER = ATTRIBUTES.register(
            "base_attack_power",
            () -> new RangedAttribute(
                    "attribute.damagemodernization.base_attack_power",
                    1.0D, 0.0D, 1_000_000.0D)
                    .setSyncable(true));

    /**
     * 固定攻击力：直接加在「基础攻击力 × (1 + 百分比)」之上的固定值。
     *
     * <p>默认 0.0。
     */
    public static final Holder<Attribute> ATTACK_POWER_FLAT = ATTRIBUTES.register(
            "attack_power_flat",
            () -> new RangedAttribute(
                    "attribute.damagemodernization.attack_power_flat",
                    0.0D, -1_000_000.0D, 1_000_000.0D)
                    .setSyncable(true));

    /**
     * 攻击力百分比提升：作用于「基础攻击力」的百分比加成。
     *
     * <p>采用 {@link PercentageAttribute}，因此 0.1 会被显示为 +10%。
     * 默认 0.0（即 +0%）。
     *
     * <p>这是你要求的「后续百分比提升攻击力以基础攻击力为准」的落地方式：
     * 提升的是基础攻击力，而不是原版那种对最终伤害的模糊加成。
     */
    public static final Holder<Attribute> ATTACK_POWER_PERCENT = ATTRIBUTES.register(
            "attack_power_percent",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.attack_power_percent",
                    0.0D, -1.0D, 1_000.0D)
                    .setSyncable(true));

    /**
     * 伤害提升（加算区）：同一乘区内的多个来源在此相加。
     *
     * <p>实际参与运算的是 {@code (1 + 本属性)}。
     * 例如值为 0.25 表示该乘区为 ×1.25。默认 0.0。
     */
    public static final Holder<Attribute> DAMAGE_AMPLIFIER = ATTRIBUTES.register(
            "damage_amplifier",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.damage_amplifier",
                    0.0D, -1.0D, 1_000.0D)
                    .setSyncable(true));

    /**
     * 伤害倍率（乘算区）：作为独立乘数直接乘在总伤害上。
     *
     * <p>与 {@link #DAMAGE_AMPLIFIER} 不同，这里是<b>直接相乘</b>，
     * 多个来源会以乘法叠加（可通过 AttributeModifier 的 MULTIPLY_TOTAL 操作实现）。
     * 默认 1.0（即 ×1，不影响伤害）。
     */
    public static final Holder<Attribute> DAMAGE_MULTIPLIER = ATTRIBUTES.register(
            "damage_multiplier",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.damage_multiplier",
                    Config.damageMultiplierOrDefault(), 0.0D, 1_000.0D)
                    .setSyncable(true));

    /**
     * 暴击率：攻击时触发暴击的概率。
     *
     * <p>采用 {@link PercentageAttribute}，0.05 显示为 +5%。
     * 默认 0.05（5%），偏保守以免破坏原版手感。
     *
     * <p>启用后，原版「跳跃下劈必定暴击 ×1.5」会被接管，
     * 改为由本属性 + {@link #CRIT_DAMAGE} 决定，避免双重计算。
     */
    public static final Holder<Attribute> CRIT_CHANCE = ATTRIBUTES.register(
            "crit_chance",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.crit_chance",
                    Config.critChanceOrDefault(), 0.0D, 1.0D)
                    .setSyncable(true));

    /**
     * 暴击伤害：暴击时的伤害倍率。
     *
     * <p>默认 1.5（与原版暴击倍率一致），即暴击区为 ×1.5；设为 2.0 则表示两倍。
     *
     * <p>该属性允许被削减到 1.0 以下以表达减益，但真正施加到伤害上的
     * 暴击乘区有下限 1.0（见 {@code DamagePipeline#computeZones}），
     * 因此「暴击」永远不会比不暴击伤害更低。
     */
    public static final Holder<Attribute> CRIT_DAMAGE = ATTRIBUTES.register(
            "crit_damage",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.crit_damage",
                    Config.critDamageOrDefault(), 0.0D, 1_000.0D)
                    .setSyncable(true));

    private DMAttributes() {
    }
}
