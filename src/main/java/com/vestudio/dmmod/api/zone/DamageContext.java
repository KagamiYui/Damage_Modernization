package com.vestudio.dmmod.api.zone;

import javax.annotation.Nullable;

import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 伤害乘区的运算上下文，提供给 {@link IDamageZone} 与其他 mod 读取/修改。
 *
 * <p>这是一个<b>可变</b>对象：乘区实现可以通过 {@code setXxx} 方法调整本次伤害的各个要素。
 * 可变性是有意为之——多个乘区常常需要协同（例如「破甲」乘区降低了护甲穿透，
 * 后续乘区应看到更新后的值）。
 *
 * <h2>生命周期</h2>
 * 每次伤害结算创建一个新实例，结算结束后即被丢弃。
 * <b>不要跨事件保存该对象</b>，它的内容只对当次伤害有效。
 *
 * <h2>线程安全</h2>
 * 伤害结算总是在服务端逻辑线程上进行，因此本对象不需要内部同步。
 * 但请勿把它发布到其他线程。
 */
public final class DamageContext {

    private final DamageSource source;

    @Nullable
    private final LivingEntity attacker;

    private final LivingEntity victim;

    /** 是否属于「生物攻击」而非环境伤害。 */
    private final boolean meleeAttack;

    /** 环境伤害使用：原始伤害直接充当攻击力区。 */
    private final boolean environmental;

    /** 基础攻击力（已由原版攻击伤害转换而来）。 */
    private double baseAttackPower;

    /** 百分比攻击力提升（0.1 = +10%），作用于基础攻击力。 */
    private double attackPowerPercent;

    /** 固定攻击力加值。 */
    private double attackPowerFlat;

    /** 伤害提升（加算区），0.25 = 该乘区 ×1.25。 */
    private double damageAmplifier;

    /** 伤害倍率（乘算区），直接作为乘数。 */
    private double damageMultiplier;

    /** 本次是否暴击。 */
    private boolean critical;

    /** 暴击伤害倍率。 */
    private double critDamage;

    /** 最终伤害覆盖值；为 null 表示使用乘区运算结果。 */
    @Nullable
    private Double finalDamageOverride;

    /** 连锁伤害标记，避免同一伤害被重复处理。 */
    private boolean processed;

    DamageContext(DamageSource source,
                  @Nullable LivingEntity attacker,
                  LivingEntity victim,
                  boolean meleeAttack,
                  boolean environmental,
                  double baseAttackPower,
                  double attackPowerPercent,
                  double attackPowerFlat,
                  double damageAmplifier,
                  double damageMultiplier,
                  boolean critical,
                  double critDamage) {
        this.source = source;
        this.attacker = attacker;
        this.victim = victim;
        this.meleeAttack = meleeAttack;
        this.environmental = environmental;
        this.baseAttackPower = baseAttackPower;
        this.attackPowerPercent = attackPowerPercent;
        this.attackPowerFlat = attackPowerFlat;
        this.damageAmplifier = damageAmplifier;
        this.damageMultiplier = damageMultiplier;
        this.critical = critical;
        this.critDamage = critDamage;
    }

    // ------------------------------------------------------------------
    // 只读信息
    // ------------------------------------------------------------------

    /** {@return 伤害来源} */
    public DamageSource source() {
        return source;
    }

    /**
     * {@return 攻击者；环境伤害时为 null}
     *
     * <p>注意与 {@link DamageSource#getEntity()} 的区别：这里已经过类型判断，
     * 保证返回的是 {@link LivingEntity}。
     */
    @Nullable
    public LivingEntity attacker() {
        return attacker;
    }

    /** {@return 受伤实体} */
    public LivingEntity victim() {
        return victim;
    }

    /** {@return 是否为生物近战攻击} */
    public boolean isMeleeAttack() {
        return meleeAttack;
    }

    /** {@return 是否为环境伤害（摔落、火焰、中毒等）} */
    public boolean isEnvironmental() {
        return environmental;
    }

    /** {@return 攻击者是否可用（非环境伤害）} */
    public boolean hasAttacker() {
        return attacker != null;
    }

    // ------------------------------------------------------------------
    // 攻击力区
    // ------------------------------------------------------------------

    /** {@return 基础攻击力（未经百分比放大）} */
    public double baseAttackPower() {
        return baseAttackPower;
    }

    /**
     * 设置基础攻击力。
     *
     * <p>其他 mod 若想完全接管攻击力来源（例如自定义职业系统），可在此覆写。
     *
     * @param value 新的基础攻击力
     */
    public void setBaseAttackPower(double value) {
        if (Double.isFinite(value) && value >= 0.0D) {
            this.baseAttackPower = value;
        }
    }

    /** {@return 百分比攻击力提升} */
    public double attackPowerPercent() {
        return attackPowerPercent;
    }

    /**
     * 累加百分比攻击力提升。
     *
     * <p>使用<b>累加</b>而非覆盖，便于多个 mod 各自贡献一部分加成。
     *
     * @param delta 增量，0.1 表示 +10%
     */
    public void addAttackPowerPercent(double delta) {
        if (Double.isFinite(delta)) {
            this.attackPowerPercent += delta;
        }
    }

    /** {@return 固定攻击力加值} */
    public double attackPowerFlat() {
        return attackPowerFlat;
    }

    /**
     * 累加固定攻击力。
     *
     * @param delta 增量
     */
    public void addAttackPowerFlat(double delta) {
        if (Double.isFinite(delta)) {
            this.attackPowerFlat += delta;
        }
    }

    // ------------------------------------------------------------------
    // 伤害提升区
    // ------------------------------------------------------------------

    /** {@return 伤害提升（加算区）} */
    public double damageAmplifier() {
        return damageAmplifier;
    }

    /**
     * 累加伤害提升。
     *
     * <p>同一乘区内的多个来源应当<b>累加</b>，因此提供该方法而非 setter。
     *
     * @param delta 增量，0.25 表示该乘区 +25%
     */
    public void addDamageAmplifier(double delta) {
        if (Double.isFinite(delta)) {
            this.damageAmplifier += delta;
        }
    }

    // ------------------------------------------------------------------
    // 伤害倍率区
    // ------------------------------------------------------------------

    /** {@return 伤害倍率} */
    public double damageMultiplier() {
        return damageMultiplier;
    }

    /**
     * 乘以一个伤害倍率。
     *
     * <p>该乘区是<b>乘算</b>的：多次调用会连乘，符合「独立乘区」的语义。
     *
     * @param factor 倍率，1.2 表示 ×1.2
     */
    public void multiplyDamageMultiplier(double factor) {
        if (Double.isFinite(factor) && factor >= 0.0D) {
            this.damageMultiplier *= factor;
        }
    }

    /**
     * 直接设置伤害倍率（覆盖式）。
     *
     * <p>仅在需要完全接管该乘区时使用；通常应优先使用
     * {@link #multiplyDamageMultiplier(double)} 以保持可组合性。
     *
     * @param value 新的倍率
     */
    public void setDamageMultiplier(double value) {
        if (Double.isFinite(value) && value >= 0.0D) {
            this.damageMultiplier = value;
        }
    }

    // ------------------------------------------------------------------
    // 暴击区
    // ------------------------------------------------------------------

    /** {@return 本次是否暴击} */
    public boolean isCritical() {
        return critical;
    }

    /**
     * 设置本次是否暴击。
     *
     * @param critical 是否暴击
     */
    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    /**
     * {@return 暴击伤害倍率（<b>原始存储值</b>，未做下限处理）}
     *
     * <p>该值可能低于 1.0（例如被减益效果削减）。真正参与伤害计算的下限
     * 由 {@link DamagePipeline#computeZones} 负责，那里会钳制为不小于 1.0，
     * 确保「暴击不会比不暴击伤害更低」。
     */
    public double critDamage() {
        return critDamage;
    }

    /**
     * 设置暴击伤害倍率。
     *
     * <p>允许传入低于 1.0 的值，用于表达「降低暴击伤害」的减益效果；
     * 按需求，加成可以为负。最终施加到伤害上的生效值会在
     * {@link DamagePipeline#computeZones} 中被钳制为不低于 1.0。
     *
     * <p>注意：本方法是<b>覆盖</b>而非累加。若希望在既有数值上叠加，
     * 请传 {@code context.critDamage() + delta}。
     *
     * @param value 存储值，1.5 表示暴击造成 1.5 倍伤害
     */
    public void setCritDamage(double value) {
        if (Double.isFinite(value)) {
            this.critDamage = value;
        }
    }

    // ------------------------------------------------------------------
    // 最终伤害接管
    // ------------------------------------------------------------------

    /**
     * 直接指定最终伤害，跳过乘区运算。
     *
     * <p>这是给需要完全接管伤害的 mod 的逃生舱（例如「固定伤害」类效果）。
     * 一经调用，后续乘区修改不再影响最终结果。
     *
     * @param value 最终伤害；负值会被视为 0
     */
    public void overrideFinalDamage(double value) {
        this.finalDamageOverride = Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    /**
     * {@return 被显式指定的最终伤害；未指定时为 null}
     */
    @Nullable
    public Double finalDamageOverride() {
        return finalDamageOverride;
    }

    /** {@return 是否已被某个乘区/监听器指定了最终伤害} */
    public boolean hasFinalDamageOverride() {
        return finalDamageOverride != null;
    }

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------

    /** {@return 是否已处理过，用于防止重复结算} */
    public boolean isProcessed() {
        return processed;
    }

    /** 标记为已处理。由管线在结算完成后调用。 */
    public void markProcessed() {
        this.processed = true;
    }

    /**
     * 读取攻击者（或受害方）身上某个属性的值，作为便捷方法。
     *
     * @param entity    实体
     * @param attribute 属性
     * @return 属性值；实体为 null 时返回默认值
     */
    public static double attributeOf(@Nullable LivingEntity entity,
                                     net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        return entity == null ? 0.0D : entity.getAttributeValue(attribute);
    }

    /**
     * {@return 本上下文对应的基础攻击力属性值，便于其他 mod 参考}
     */
    public double baseAttackPowerAttribute() {
        return attributeOf(attacker, DMAttributes.BASE_ATTACK_POWER);
    }

    /**
     * {@return 用于日志与调试的简短标识}
     */
    public ResourceLocation sourceId() {
        return source.typeHolder().unwrapKey()
                .map(key -> key.location())
                .orElse(ResourceLocation.fromNamespaceAndPath("minecraft", "unknown"));
    }
}
