package com.vestudio.dmmod.api;

import java.util.List;

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

    // ==================================================================
    // 生命值
    // ==================================================================
    //
    // 与攻击力同样的思路：原版 max_health 的语义是「最终血量」，
    // 这里新增 base_health 承载「基础生命值」，再叠加百分比与固定加成，
    // 算出的结果写回 max_health。

    /**
     * 基础生命值：生命值计算的基准，包含装备等提供的生命加成。
     *
     * <p>默认 20.0（与原版玩家血量一致）。
     * 可成长的百分比加成作用于该值，而非直接作用于最终血量。
     */
    public static final Holder<Attribute> BASE_HEALTH = ATTRIBUTES.register(
            "base_health",
            () -> new RangedAttribute(
                    "attribute.damagemodernization.base_health",
                    20.0D, 0.0D, 1_000_000.0D)
                    .setSyncable(true));

    /**
     * 生命值百分比提升：作用于基础生命值的百分比加成。
     *
     * <p>采用 {@link PercentDisplayAttribute}，因此 0.1 显示为 +10%。默认 0.0。
     */
    public static final Holder<Attribute> HEALTH_PERCENT = ATTRIBUTES.register(
            "health_percent",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.health_percent",
                    0.0D, -1.0D, 1_000.0D)
                    .setSyncable(true));

    /**
     * 固定生命值：直接加在「基础生命值 × (1 + 百分比)」之上的固定值。
     *
     * <p>默认 0.0。
     */
    public static final Holder<Attribute> HEALTH_FLAT = ATTRIBUTES.register(
            "health_flat",
            () -> new RangedAttribute(
                    "attribute.damagemodernization.health_flat",
                    0.0D, -1_000_000.0D, 1_000_000.0D)
                    .setSyncable(true));

    /**
     * 物理伤害提升：作为<b>伤害提升区</b>的子项，只在物理伤害时计入。
     *
     * <p>采用加算语义：与通用增伤相加，而非相乘。
     * 例如通用 +10% 与物理 +10% 合计为 +20%。
     */
    public static final Holder<Attribute> PHYSICAL_AMPLIFIER = ATTRIBUTES.register(
            "physical_amplifier",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.physical_amplifier",
                    0.0D, -1.0D, 1_000.0D)
                    .setSyncable(true));

    /**
     * 魔法伤害提升：作为<b>伤害提升区</b>的子项，只在带魔法类型时计入。
     *
     * <p>原版的药水与状态效果伤害（{@code magic}、{@code indirect_magic}）
     * 即属于魔法类型。
     */
    public static final Holder<Attribute> MAGIC_AMPLIFIER = ATTRIBUTES.register(
            "magic_amplifier",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.magic_amplifier",
                    0.0D, -1.0D, 1_000.0D)
                    .setSyncable(true));

    /**
     * 物理伤害减免：受到物理伤害时的减免比例。
     *
     * <p>作为<b>承伤乘区</b>的子项，只在物理伤害时生效。
     * 0.4 表示减免 40%（承伤乘数 0.6）。
     */
    public static final Holder<Attribute> PHYSICAL_RESISTANCE = ATTRIBUTES.register(
            "physical_resistance",
            () -> new PercentDisplayAttribute(
                    "attribute.damagemodernization.physical_resistance",
                    0.0D, -1.0D, 1.0D)
                    .setSyncable(true));

    // ==================================================================
    // 公式变量
    // ==================================================================

    /**
     * {@return 公式中引用某个属性时使用的变量名}
     *
     * <p>变量名即属性 ID 的路径部分，例如
     * {@code damagemodernization:base_attack_power} → {@code base_attack_power}。
     * 这样数据文件里的公式与属性定义能自然对应。
     *
     * @param attribute 属性
     */
    public static String variableName(Holder<Attribute> attribute) {
        return attribute.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("unknown");
    }

    /**
     * {@return 增减伤（加算区）相关的属性列表}
     *
     * <p>该乘区<b>同时容纳攻击方的增伤与受害方的减伤</b>，
     * 因此承伤公式需要同时读取双方这些属性才能把它们相加。
     */
    public static List<Holder<Attribute>> amplifierAttributes() {
        return List.of(
                DAMAGE_AMPLIFIER,
                PHYSICAL_AMPLIFIER,
                MAGIC_AMPLIFIER);
    }

    /**
     * {@return 攻击相关的属性列表，供公式注入变量}
     */
    public static List<Holder<Attribute>> attackAttributes() {
        return List.of(
                BASE_ATTACK_POWER,
                ATTACK_POWER_PERCENT,
                ATTACK_POWER_FLAT,
                DAMAGE_AMPLIFIER,
                DAMAGE_MULTIPLIER,
                CRIT_CHANCE,
                CRIT_DAMAGE,
                PHYSICAL_AMPLIFIER,
                MAGIC_AMPLIFIER);
    }

    /**
     * {@return 防御（承伤）相关的属性列表，供公式注入变量}
     */
    public static List<Holder<Attribute>> defenseAttributes() {
        return List.of(
                PHYSICAL_RESISTANCE,
                BASE_HEALTH,
                HEALTH_PERCENT,
                HEALTH_FLAT);
    }

    /**
     * {@return 生命值相关的属性列表，供公式注入变量}
     */
    public static List<Holder<Attribute>> healthAttributes() {
        return List.of(BASE_HEALTH, HEALTH_PERCENT, HEALTH_FLAT);
    }

    private DMAttributes() {
    }
}
