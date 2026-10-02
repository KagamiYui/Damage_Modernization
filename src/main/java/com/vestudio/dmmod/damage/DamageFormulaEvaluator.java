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
 *   <li>属性值：{@code attack_power_percent}、{@code damage_multiplier} 等，取自攻击者；</li>
 *   <li>原版数值：{@code attack_damage}（原版攻击伤害总值），取自攻击者；</li>
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

        ZoneEvaluator evaluator = new ZoneEvaluator();

        // 攻击者的属性（环境伤害没有攻击者，此时全部注入 0）。
        evaluator.attributes(attacker, DMAttributes.attackAttributes());

        // 攻击力区。
        //
        // 近战：直接取原版攻击伤害的<b>总值</b>——武器加成、药水、以及我们的
        // 百分比/固定值加成全都以修饰符形式挂在它上面，原版已经算好了。
        // 因此不再需要自己维护一份「基础攻击力」，也不需要那个镜像步骤。
        //
        // 投射物与环境伤害：攻击力区由上下文给定（投射物自身的基础伤害 /
        // 原始伤害），上下文里的百分比与固定值还没应用，这里手动合成一次。
        if (attacker != null && context.isMeleeAttack()) {
            double attackDamage = DamageContext.attributeOf(attacker,
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            evaluator.context("attack_damage", attackDamage);

            // 兼容旧数据文件：早期写的是
            // {@code base_attack_power * (1 + attack_power_percent) + attack_power_flat}。
            // 把 {@code base_attack_power} 也指向同一个总值，并把那两个加成项归零——
            // 它们已经作为修饰符并入总值，若仍按旧式再乘一次就会重复计算。
            evaluator.context("base_attack_power", attackDamage);
            evaluator.context("attack_power_percent", 0.0D);
            evaluator.context("attack_power_flat", 0.0D);
        } else {
            // 上下文给定的攻击力区。
            //
            // 必须注入 {@code attack_damage}：默认公式的攻击力区引用的就是它，
            // 缺了它会因「变量未提供」而把<b>整个攻击力区按 1.0 跳过</b>。
            double base = context.baseAttackPower();
            double zone = base * (1.0D + context.attackPowerPercent()) + context.attackPowerFlat();
            evaluator.context("attack_damage", zone);
            evaluator.context("base_attack_power", zone);
            evaluator.context("attack_power_percent", 0.0D);
            evaluator.context("attack_power_flat", 0.0D);
        }

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

        // 外部来源提供的暴伤加成（例如星辉的 critical_hit_damage perk）。
        // 它的语义与 crit_damage_bonus 属性完全相同——都是暴击区里的加算子项，
        // 因此并入同一个变量，而不是另开一个乘区。
        double externalCritDamage = context.externalCritDamageBonus();
        if (externalCritDamage != 0.0D) {
            evaluator.context("crit_damage_bonus",
                    DamageContext.attributeOf(attacker, DMAttributes.CRIT_DAMAGE_BONUS)
                            + externalCritDamage);
        }

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
