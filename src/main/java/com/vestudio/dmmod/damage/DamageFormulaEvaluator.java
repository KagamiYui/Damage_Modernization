package com.vestudio.dmmod.damage;

import java.util.Map;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.formula.ZoneEvaluator;
import com.vestudio.dmmod.formula.ZoneScope;

import net.minecraft.world.entity.LivingEntity;

/**
 * 伤害公式的乘区求值。
 *
 * <h2>契约</h2>
 * 每个乘区输出一个<b>乘数</b>，最终伤害为四者连乘：
 * <pre>
 *   最终伤害 = 攻击力区 × 伤害提升区 × 伤害倍率区 × 暴击区
 * </pre>
 * 其中攻击力区输出的是<b>绝对项</b>（点数当乘数，如钻石剑 7），
 * 其余三者输出相对倍率。
 *
 * <h2>变量</h2>
 * <ul>
 *   <li>属性值：{@code base_attack_power}、{@code attack_power_percent} 等，取自攻击者；</li>
 *   <li>上下文：{@code is_critical}、{@code raw_damage}、{@code is_environmental}；</li>
 *   <li>全局系数：{@code amplifier_bonus}、{@code multiplier_factor}、{@code crit_bonus}，
 *       来自配置项，使原本独立的调节旋钮仍可被公式引用。</li>
 * </ul>
 *
 * <p>本类无静态可变状态，每次攻击新建实例，不存在跨实体串值。
 */
public final class DamageFormulaEvaluator {

    /**
     * 求值一次伤害结算。
     *
     * @param context 伤害上下文
     * @return 最终伤害
     */
    public static double evaluate(DamageContext context) {
        LivingEntity attacker = context.attacker();

        // 先把武器的攻击伤害解析/镜像进 base_attack_power。
        // 公式读的是属性总值，若跳过这一步，武器加成不会体现，
        // 所有攻击都会退化成空手攻击力。
        if (attacker != null) {
            BaseAttackPowerConverter.resolveBaseAttackPower(attacker);
        }

        ZoneEvaluator evaluator = new ZoneEvaluator();

        // 攻击者的属性（环境伤害没有攻击者，此时全部注入 0）。
        evaluator.attributes(attacker, DMAttributes.attackAttributes());

        // 受害者的「暴击伤害减免」。
        // 它虽然是防守方属性，但按需求应当在<b>暴击区内直接削减系数</b>，
        // 因此这里必须让伤害公式读到它。
        if (context.victim() != null) {
            evaluator.attributes(context.victim(),
                    java.util.List.of(DMAttributes.CRIT_DAMAGE_TAKEN_REDUCTION));
        }

        // 上下文变量。
        evaluator.context("is_critical", context.isCritical() ? 1.0D : 0.0D);
        evaluator.context("is_environmental", context.isEnvironmental() ? 1.0D : 0.0D);
        evaluator.context("raw_damage", context.baseAttackPower());

        // 伤害类型：注入为 has_xxx 变量，供各类型增伤子项使用。
        // 类型可以同时成立（例如「同时视为物理与魔法」），
        // 因此这里是多个并行的 0/1，而不是互斥的单选。
        injectDamageTypes(evaluator, context);

        // 原先独立的全局系数，现作为变量供公式引用。
        evaluator.context("amplifier_bonus", Config.DAMAGE_AMPLIFIER_ZONE_BONUS.get());
        evaluator.context("multiplier_factor", Config.DAMAGE_MULTIPLIER_ZONE_FACTOR.get());
        evaluator.context("crit_bonus", Config.CRIT_ZONE_DAMAGE_BONUS.get());

        Map<String, Double> outputs = evaluator.evaluate(ZoneScope.DAMAGE);

        // 把暴击区的实际结果回写到上下文，
        // 供承伤侧计算「削减暴击增益」时参照。
        Double critOutput = outputs.get(ZoneIds.CRITICAL);
        if (critOutput != null) {
            context.setCritMultiplier(critOutput);
        }

        double result = 1.0D;
        for (double multiplier : outputs.values()) {
            result *= multiplier;
        }

        if (!Double.isFinite(result) || result < 0.0D) {
            return 0.0D;
        }
        return result;
    }

    /**
     * 把伤害类型注入为变量。
     *
     * <p>变量名为 {@code has_<类型>}，例如 {@code has_physical}、{@code has_magic}。
     * 多个类型可以同时为 1，因此公式中相应子项会一并计入。
     *
     * <h2>为什么所有已注册类型都要注入</h2>
     * 公式里写的是 {@code has_physical ? ... : 0}，
     * 若某类型当前不成立就<b>不注入</b>该变量，公式一引用它就会抛「变量未提供」，
     * 导致<b>整个乘区被跳过</b>（按中性值 1.0 处理），
     * 从而把该乘区的所有加成一起丢掉。
     *
     * <p>因此这里注入<b>全部已注册类型</b>：成立的给 1，不成立的给 0。
     * 这样公式无论走到哪个分支都能正常求值。
     *
     * @param evaluator 求值器
     * @param context   伤害上下文
     */
    static void injectDamageTypes(ZoneEvaluator evaluator, DamageContext context) {
        com.vestudio.dmmod.api.damagetype.DamageTypeSet present = context.damageTypes();

        for (net.minecraft.resources.ResourceLocation type
                : com.vestudio.dmmod.api.damagetype.DamageTypeRegistry.knownTypes()) {
            // 变量名取类型的路径部分：damagemodernization:physical → has_physical
            evaluator.context("has_" + type.getPath(), present.has(type) ? 1.0D : 0.0D);
        }
    }
}
