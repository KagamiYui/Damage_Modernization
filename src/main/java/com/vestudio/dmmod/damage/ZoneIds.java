package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.formula.DataRepository;
import com.vestudio.dmmod.formula.ZoneScope;

/**
 * 内置乘区的标识常量。
 *
 * <p>把这些字符串集中在一处，避免加载器与取用方各写一份而写错。
 * 它们同时也是数据文件中 {@code id} 字段的取值。
 */
public final class ZoneIds {

    // ---- 伤害公式的乘区 ----
    /** 攻击力区。 */
    public static final String ATTACK_POWER = "attack_power";
    /** 伤害提升区。 */
    public static final String AMPLIFIER = "amplifier";
    /** 伤害倍率区。 */
    public static final String MULTIPLIER = "multiplier";
    /** 暴击区。 */
    public static final String CRITICAL = "critical";

    // ---- 生命值的乘区 ----
    /** 基础生命值缩放区。 */
    public static final String HEALTH_BASE_SCALE = "base_scale";
    /** 生命值提升区。 */
    public static final String HEALTH_BONUS = "bonus";

    private ZoneIds() {
    }

    /**
     * {@return 伤害公式的乘区是否齐备}
     *
     * <h2>为什么不再要求 amplifier</h2>
     * 「增伤与减伤」已合并为同一个加算区，并移到 {@code taken} 作用域
     * （只有在那里才能同时读到攻击者的增伤与受害者的减伤）。
     * 因此伤害公式只剩攻击力区、伤害倍率区、暴击区三项。
     *
     * <p>若这里仍要求 {@code damage/amplifier}，检查会恒为 false，
     * 导致数据驱动的伤害公式<b>永远不会被使用</b>而一直走兜底。
     */
    public static boolean damageZonesPresent() {
        return DataRepository.zone(ZoneScope.DAMAGE, ATTACK_POWER) != null
                && DataRepository.zone(ZoneScope.DAMAGE, MULTIPLIER) != null
                && DataRepository.zone(ZoneScope.DAMAGE, CRITICAL) != null;
    }

    /**
     * {@return 增减伤（加算区）是否存在}
     *
     * <p>该乘区位于 {@code taken} 作用域，同时容纳攻守双方的贡献。
     */
    public static boolean amplifierZonePresent() {
        return DataRepository.zone(ZoneScope.TAKEN, AMPLIFIER) != null;
    }

    /**
     * {@return 内置的生命值乘区是否齐备}
     */
    public static boolean healthZonesPresent() {
        return DataRepository.zone(ZoneScope.HEALTH, HEALTH_BASE_SCALE) != null
                && DataRepository.zone(ZoneScope.HEALTH, HEALTH_BONUS) != null;
    }
}
