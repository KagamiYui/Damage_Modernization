package com.vestudio.dmmod;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Damage_Modernization 的配置。
 *
 * <h2>设计目标</h2>
 * 每个乘区的<b>默认数值</b>与<b>开关</b>都在这里暴露，
 * 模组包作者无需写代码即可调整伤害曲线。
 *
 * <p>所有开关都提供「关闭后回到接近原版」的退路，
 * 便于在不卸载 mod 的情况下排查与其他模组的冲突。
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ==================================================================
    // 总开关
    // ==================================================================

    public static final ModConfigSpec.BooleanValue ENABLE_FOUR_ZONE_MODEL = BUILDER
            .comment(
                    "总开关：是否用四乘区模型（攻击力 × 伤害提升 × 伤害倍率 × 暴击伤害）取代原版固定攻击伤害。",
                    "关闭后所有伤害计算回到原版行为，但属性与 API 仍然可用。")
            .define("enableFourZoneModel", true);

    public static final ModConfigSpec.BooleanValue APPLY_ZONES_TO_ENVIRONMENTAL_DAMAGE = BUILDER
            .comment(
                    "是否让环境伤害（摔落、火焰、中毒、虚空等）也经过四乘区管线。",
                    "环境伤害没有攻击者，因此其「攻击力区」直接采用原始伤害值，",
                    "但受害方的伤害提升/伤害倍率乘区依然会生效。",
                    "关闭后环境伤害保持原版数值。")
            .define("applyZonesToEnvironmentalDamage", true);

    // ==================================================================
    // 攻击力区
    // ==================================================================

    public static final ModConfigSpec.BooleanValue ENABLE_ATTACK_POWER_ZONE = BUILDER
            .comment("是否启用攻击力区。")
            .define("attackPowerZone.enabled", true);

    public static final ModConfigSpec.DoubleValue ATTACK_POWER_ZONE_SCALE = BUILDER
            .comment(
                    "攻击力区全局缩放系数，作用于「基础攻击力」本身。",
                    "默认 1.0，即完全保留原版数值手感（空手 1、石剑 4、钻石剑 6、下界合金剑 7）。",
                    "调大可整体放大伤害区间，调小则整体压缩。")
            .defineInRange("attackPowerZone.scale", 1.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.ConfigValue<String> ATTACK_POWER_FORMULA = BUILDER
            .comment(
                    "攻击力区的合成形态，可选值：",
                    "  FULL         —— 基础攻击力 × (1 + 百分比提升) + 固定加值（默认）",
                    "  FLAT_ONLY    —— 忽略百分比提升，只保留基础值与固定加值",
                    "  PERCENT_ONLY —— 忽略固定加值，只保留基础值与百分比提升")
            .define("attackPowerZone.formula", "FULL",
                    o -> o instanceof String s
                            && List.of("FULL", "FLAT_ONLY", "PERCENT_ONLY").contains(s));

    // ==================================================================
    // 伤害提升区（加算区）
    // ==================================================================

    public static final ModConfigSpec.BooleanValue ENABLE_AMPLIFIER_ZONE = BUILDER
            .comment(
                    "是否启用伤害提升区（加算区）。",
                    "该乘区同时容纳攻击方的增伤与受害方的减伤，二者相加成一个总和后再换算。")
            .define("damageAmplifierZone.enabled", true);

    public static final ModConfigSpec.DoubleValue REDUCTION_CURVE_COEFFICIENT = BUILDER
            .comment(
                    "减伤超出 50% 之后，对数曲线的陡峭程度。",
                    "增减伤换算规则：",
                    "  Σ ≥ -0.5        →  1 + Σ                   线性（面板比例即实际效果）",
                    "  Σ < -0.5        →  0.5 / (1 + k·(|Σ|-0.5))  对数式衰减，恒大于 0",
                    "本项即公式中的 k：",
                    "  k = 2.0（默认）→ 减免 100% 时承伤 0.25，减免 200% 时承伤 0.125",
                    "  k 越大         → 超出 50% 后衰减越快（堆减伤越不划算）",
                    "  k 越小         → 衰减越慢（越接近但不等于免疫）",
                    "k 为 0 时超出部分恒为 0.5（不再衰减）。")
            .defineInRange("damageAmplifierZone.reductionCurveCoefficient", 2.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue DAMAGE_AMPLIFIER_ZONE_BONUS = BUILDER
            .comment(
                    "伤害提升区的全局加成，作用于所有伤害。",
                    "0.0 表示不额外加成（默认）；0.25 表示所有伤害 +25%。",
                    "该值会被计入加算区，与属性提供的伤害提升相加。")
            .defineInRange("damageAmplifierZone.globalBonus", 0.0D, -1.0D, 1000.0D);

    // ==================================================================
    // 伤害倍率区（乘算区）
    // ==================================================================

    public static final ModConfigSpec.BooleanValue ENABLE_MULTIPLIER_ZONE = BUILDER
            .comment("是否启用伤害倍率区（乘算区）。")
            .define("damageMultiplierZone.enabled", true);

    public static final ModConfigSpec.DoubleValue DAMAGE_MULTIPLIER_ZONE_FACTOR = BUILDER
            .comment(
                    "伤害倍率区的全局系数，直接乘在总伤害上。",
                    "1.0 表示不影响伤害（默认）；1.5 表示所有伤害 ×1.5。",
                    "与加算区的区别：这里是乘法叠加，多个来源彼此连乘。")
            .defineInRange("damageMultiplierZone.globalFactor", 1.0D, 0.0D, 1000.0D);

    // ==================================================================
    // 暴击区
    // ==================================================================

    public static final ModConfigSpec.BooleanValue ENABLE_CRIT_ZONE = BUILDER
            .comment(
                    "是否启用暴击乘区。",
                    "开启后，原版「跳跃下劈必定暴击 ×1.5」会被接管，",
                    "改由 attackAttributes.critChance（暴击率）与 critDamage（暴击伤害）决定，避免双重暴击。",
                    "关闭后不产生暴击，暴击区恒为 1.0。")
            .define("critZone.enabled", true);

    public static final ModConfigSpec.DoubleValue CRIT_ZONE_DAMAGE_BONUS = BUILDER
            .comment(
                    "暴击伤害的全局加成，以加法方式叠加到暴击倍率上。",
                    "0.0 表示不额外加成（默认）；0.5 表示暴击倍率 +0.5。",
                    "允许为负，用于表达「降低暴击伤害」的减益效果。",
                    "注意：生效后的暴击乘区有下限 1.0，",
                    "因此即使加成很大导致结果低于 1.0，暴击也不会比不暴击伤害更低。")
            .defineInRange("critZone.globalDamageBonus", 0.0D, -1000.0D, 1000.0D);

    // ==================================================================
    // 属性默认值
    // ==================================================================

    public static final ModConfigSpec.DoubleValue DEFAULT_CRIT_CHANCE = BUILDER
            .comment(
                    "暴击率的默认值，适用于所有未被单独配置的生物。",
                    "0.05 表示 5%（默认）；1.0 表示必定暴击。")
            .defineInRange("defaults.critChance", 0.05D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue DEFAULT_CRIT_DAMAGE = BUILDER
            .comment(
                    "暴击伤害倍率的默认值，适用于所有未被单独配置的生物。",
                    "1.5 表示暴击造成 1.5 倍伤害（与原版一致，默认）；2.0 表示两倍。",
                    "允许设为 1.0 以下以表达减益，但生效的暴击乘区下限为 1.0。")
            .defineInRange("defaults.critDamage", 1.5D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue DEFAULT_DAMAGE_MULTIPLIER = BUILDER
            .comment(
                    "伤害倍率属性的默认值。",
                    "1.0 表示不影响伤害（默认）。")
            .defineInRange("defaults.damageMultiplier", 1.0D, 0.0D, 1000.0D);

    // ==================================================================
    // 生命值
    // ==================================================================

    public static final ModConfigSpec.DoubleValue HEALTH_SCALE = BUILDER
            .comment(
                    "基础生命值的全局缩放，作用于所有生物。",
                    "1.0 表示保持原版血量（默认）；1.5 表示所有生物基础生命值 +50%。",
                    "这是独立的缩放乘区，与「生命值百分比提升」互不影响：",
                    "  基础生命值 = 原版血量 × 本系数",
                    "  最终生命值 = 基础生命值 × (1 + 生命值百分比) + 固定生命值")
            .defineInRange("health.baseScale", 1.0D, 0.0D, 1000.0D);

    // ==================================================================
    // 调试
    // ==================================================================

    public static final ModConfigSpec.BooleanValue LOG_ZONE_CALCULATION = BUILDER
            .comment(
                    "是否把每次伤害的四乘区明细打印到日志。",
                    "用于调试与数值验证，正式游玩建议关闭以避免刷屏。")
            .define("debug.logZoneCalculation", false);

    public static final ModConfigSpec.BooleanValue LOG_ZONE_REGISTRATION = BUILDER
            .comment(
                    "是否在启动时打印已注册的伤害乘区列表。",
                    "便于确认其他 mod 的乘区是否成功挂载。")
            .define("debug.logZoneRegistration", true);

    /** 构建好的配置规格，供 mod 主类注册。 */
    static final ModConfigSpec SPEC = BUILDER.build();

    // ==================================================================
    // 安全读取辅助
    // ==================================================================
    //
    // 属性在「注册阶段」就会被实例化，而配置的加载晚于注册。
    // 因此属性定义处必须使用下面这些「带兜底值」的访问器，
    // 否则会抛出 "Cannot get config value before config is loaded"。
    //
    // 配置驱动的默认值最终是在 EntityAttributeModificationEvent 中写入的
    // （见 DamageModernization#onEntityAttributeModification），
    // 那里配置已经加载完毕，可以安全读取。

    /** 暴击率的出厂默认值，与配置项默认值保持一致。 */
    public static final double FALLBACK_CRIT_CHANCE = 0.05D;

    /** 暴击伤害的出厂默认值。 */
    public static final double FALLBACK_CRIT_DAMAGE = 1.5D;

    /** 伤害倍率的出厂默认值。 */
    public static final double FALLBACK_DAMAGE_MULTIPLIER = 1.0D;

    /**
     * 安全读取一个浮点配置项。
     *
     * <p>在配置尚未加载时返回兜底值，避免属性注册阶段崩溃。
     *
     * @param value    配置项
     * @param fallback 配置未加载时使用的值
     * @return 配置值或兜底值
     */
    public static double getDoubleOr(ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.getAsDouble();
        } catch (IllegalStateException | NullPointerException e) {
            // 配置尚未加载（或访问器不可用）：使用兜底值。
            return fallback;
        }
    }

    /**
     * 安全读取一个布尔配置项。
     *
     * <p>布尔项同样可能在配置加载前被访问（例如乘区在构造阶段自查开关），
     * 此时按兜底值处理。
     *
     * @param value    配置项
     * @param fallback 配置未加载时使用的值
     * @return 配置值或兜底值
     */
    public static boolean getBooleanOr(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.getAsBoolean();
        } catch (IllegalStateException | NullPointerException e) {
            return fallback;
        }
    }

    /**
     * {@return 暴击率默认值，配置未加载时返回出厂默认}
     */
    public static double critChanceOrDefault() {
        return getDoubleOr(DEFAULT_CRIT_CHANCE, FALLBACK_CRIT_CHANCE);
    }

    /**
     * {@return 暴击伤害默认值，配置未加载时返回出厂默认}
     */
    public static double critDamageOrDefault() {
        return getDoubleOr(DEFAULT_CRIT_DAMAGE, FALLBACK_CRIT_DAMAGE);
    }

    /**
     * {@return 伤害倍率默认值，配置未加载时返回出厂默认}
     */
    public static double damageMultiplierOrDefault() {
        return getDoubleOr(DEFAULT_DAMAGE_MULTIPLIER, FALLBACK_DAMAGE_MULTIPLIER);
    }

    private Config() {
    }
}
