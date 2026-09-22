package com.vestudio.dmmod.damage;

import java.util.Map;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.formula.ZoneEvaluator;
import com.vestudio.dmmod.formula.ZoneScope;

import net.minecraft.world.entity.LivingEntity;

/**
 * 承伤公式的乘区求值（防守方）。
 *
 * <h2>与伤害公式的关系</h2>
 * <pre>
 *   最终伤害 = 伤害公式结果 × 承伤公式结果
 * </pre>
 * 承伤公式<b>只作用在结果之上</b>，不会回头改写攻击方的任何乘区——
 * 这保证了攻击方与防守方各自独立、互不污染。
 *
 * <h2>变量</h2>
 * <ul>
 *   <li>{@code final_damage}：伤害公式算完的结果（减免前的输入）</li>
 *   <li>{@code is_physical}：本次是否为物理伤害（近战武器 / 弓箭等）</li>
 *   <li>{@code is_critical}、{@code is_environmental}：本次结算的上下文</li>
 *   <li>受害者的全部本 mod 属性，如 {@code physical_resistance}</li>
 * </ul>
 */
public final class TakenFormulaEvaluator {

    private TakenFormulaEvaluator() {
    }

    /**
     * 求值承伤乘区。
     *
     * @param context     伤害上下文
     * @param finalDamage 伤害公式算出的结果
     * @return 承伤乘数（1.0 表示不改变伤害）；无承伤乘区时返回 1.0
     */
    public static double evaluate(DamageContext context, double finalDamage) {
        // 数据文件没有增减伤乘区时，退回内置的最小实现，
        // 保证「加减相加 + 曲线换算」的语义不因数据缺失而改变。
        if (!hasTakenZones()) {
            return legacyAmplifier(context);
        }

        LivingEntity victim = context.victim();
        LivingEntity attacker = context.attacker();

        ZoneEvaluator evaluator = new ZoneEvaluator()
                // 受害者的属性（减伤等）。
                .attributes(victim, DMAttributes.defenseAttributes())
                // 攻击者的增伤属性。
                // 增伤与减伤是<b>同一个加算区</b>的两种体现，因此这里必须
                // 同时读到双方的贡献，才能把它们相加成一个总和。
                .attributes(attacker, DMAttributes.amplifierAttributes())
                // 上下文。
                .context("final_damage", finalDamage)
                .context("raw_damage", context.baseAttackPower())
                .context("is_critical", context.isCritical() ? 1.0D : 0.0D)
                .context("is_environmental", context.isEnvironmental() ? 1.0D : 0.0D)
                // 全局系数：来自配置项，供公式引用。
                // 必须在此注入——该乘区已移入承伤侧，
                // 若漏掉这些变量，公式会因「变量未提供」而<b>整个被跳过</b>。
                .context("amplifier_bonus", Config.DAMAGE_AMPLIFIER_ZONE_BONUS.get())
                .context("multiplier_factor", Config.DAMAGE_MULTIPLIER_ZONE_FACTOR.get())
                .context("crit_bonus", Config.CRIT_ZONE_DAMAGE_BONUS.get())
                // 本次攻击的实际暴击倍率，供「削减暴击增益」参照。
                .context("crit_multiplier", context.critMultiplier());

        // 伤害类型：与伤害公式使用同一套变量名（has_xxx）。
        DamageFormulaEvaluator.injectDamageTypes(evaluator, context);

        Map<String, Double> outputs = evaluator.evaluate(ZoneScope.TAKEN);

        double result = 1.0D;
        for (double multiplier : outputs.values()) {
            result *= multiplier;
        }

        if (!Double.isFinite(result) || result < 0.0D) {
            return 1.0D;
        }
        return result;
    }

    /**
     * {@return 数据文件中是否定义了承伤乘区}
     */
    public static boolean hasTakenZones() {
        return !com.vestudio.dmmod.formula.DataRepository.zonesOf(ZoneScope.TAKEN).isEmpty();
    }

    /**
     * 内置的增减伤换算（数据缺失时的退路）。
     *
     * <p>语义与数据文件中的默认公式一致：
     * 攻守双方的贡献<b>相加</b>成一个总和，再交给曲线换算。
     *
     * <p>这里不读取类型子项（物理/魔法增伤）与全局加成，
     * 因为那些来自数据/配置；退路只保证最核心的「加减相加 + 曲线」成立。
     *
     * @param context 伤害上下文
     * @return 增减伤乘数
     */
    private static double legacyAmplifier(DamageContext context) {
        double sum = 0.0D;

        LivingEntity attacker = context.attacker();
        if (attacker != null) {
            sum += attributeOf(attacker, DMAttributes.DAMAGE_AMPLIFIER);
        }

        // 物理伤害才计入物理减免。
        if (context.isPhysical()) {
            sum -= attributeOf(context.victim(), DMAttributes.PHYSICAL_RESISTANCE);
        }

        return com.vestudio.dmmod.formula.FormulaEngine.amplifier(sum);
    }

    /**
     * 读取属性值；实体为 null 或属性缺失时返回 0。
     *
     * @param entity    实体
     * @param attribute 属性
     * @return 属性值
     */
    private static double attributeOf(LivingEntity entity,
                                      net.minecraft.core.Holder<
                                              net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        if (entity == null) {
            return 0.0D;
        }
        var instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }
}
