package com.vestudio.dmmod.api.zone;

/**
 * 内置乘区的执行优先级常量。
 *
 * <p>数值越小越先执行。留出间隔是为了让其他 mod 可以把乘区插入到
 * 内置乘区之间，而不必与它们抢同一个数值。
 *
 * <h2>执行顺序的意义</h2>
 * 后执行的乘区能看到先执行乘区写入 {@link DamageContext} 的值。
 * 因此「读取攻击者原始属性」的乘区应当靠前，
 * 而「基于最终伤害做百分比调整」的乘区应当靠后。
 */
public final class ZonePriorities {

    /**
     * 最早执行：修正基础攻击力来源本身。
     *
     * <p>适合完全接管攻击力计算的 mod（自定义职业、等级系统等）。
     */
    public static final int BASE_ATTACK_POWER = -1000;

    /**
     * 攻击力增益类：百分比攻击力、固定攻击力。
     */
    public static final int ATTACK_POWER = -500;

    /**
     * 伤害提升（加算区）。
     */
    public static final int DAMAGE_AMPLIFIER = 0;

    /**
     * 默认优先级，供外部乘区使用。
     */
    public static final int DEFAULT = 250;

    /**
     * 伤害倍率（乘算区）。
     */
    public static final int DAMAGE_MULTIPLIER = 500;

    /**
     * 暴击乘区。
     */
    public static final int CRITICAL = 750;

    /**
     * 最晚执行：最终伤害修正。
     *
     * <p>适合对最终结果做一次性调整的 mod。
     */
    public static final int FINAL = 1000;

    private ZonePriorities() {
    }
}
