package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.formula.DataRepository;
import com.vestudio.dmmod.formula.ZoneEvaluator;
import com.vestudio.dmmod.formula.ZoneScope;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 生命值乘区的求值（数据驱动）。
 *
 * <h2>三段职责，互不重叠</h2>
 * <pre>
 *   1. 镜像：把 max_health 的修饰符（装备、附魔、药水）复制到 base_health，
 *            使这些加成成为「基础生命值」的一部分。
 *   2. 基础生命值缩放：基础生命值 = base_health总值 × health_scale
 *   3. 生命值提升：    最终生命值 = 基础生命值 × (1 + 百分比) + 固定值
 * </pre>
 *
 * <h2>关键：结果以修饰符写回，绝不动 max_health 的基础值</h2>
 * 若改写基础值，下一轮又会把它当成输入再乘一次，形成反馈回路。
 *
 * <h2>变量</h2>
 * <ul>
 *   <li>{@code base_health}：已含装备加成的生命值总值</li>
 *   <li>{@code health_percent} / {@code health_flat}：来自属性</li>
 *   <li>{@code health_scale}：全局血量缩放，来自配置</li>
 *   <li>{@code vanilla_max_health}：原版基础血量</li>
 * </ul>
 */
public final class HealthFormulaEvaluator {

    /** 写回 max_health 的修饰符 id。 */
    private static final ResourceLocation MAX_HEALTH_MODIFIER =
            ResourceLocation.fromNamespaceAndPath(
                    DamageModernization.MODID, "dm_computed_max_health");

    /** 浮点容差。 */
    private static final double EPSILON = 1.0E-6D;

    private HealthFormulaEvaluator() {
    }

    /**
     * 求值生命值乘区，并把结果写入实体属性。
     *
     * @param entity 目标实体
     * @return 最终最大生命值；属性缺失时返回当前值
     */
    public static double evaluate(LivingEntity entity) {
        AttributeInstance maxAttr = entity.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance baseAttr = entity.getAttribute(DMAttributes.BASE_HEALTH);
        if (maxAttr == null || baseAttr == null) {
            return maxAttr == null ? 0.0D : maxAttr.getValue();
        }

        // max_health 的基础值始终是原版数值（我们从不改它），可安全地当输入。
        double vanillaMaxHealth = maxAttr.getBaseValue();

        // ---- 1. 镜像装备等加成到 base_health ----
        AttributeMirror.rebuildModifiers(baseAttr, maxAttr, AttributeMirror.HEALTH_PREFIX);
        if (Math.abs(baseAttr.getBaseValue() - vanillaMaxHealth) > EPSILON) {
            baseAttr.setBaseValue(vanillaMaxHealth);
        }

        // base_health 总值 = 原版基础值 + 装备等加成，即「基础生命值」的基准。
        // 注意此值同时包含基础值与修饰符，因此缩放后需要反算基础值来保持总值为目标。
        double baseBefore = baseAttr.getValue();

        // ---- 2. 基础生命值缩放 ----
        double scaledBase = evaluateZone(ZoneIds.HEALTH_BASE_SCALE, new ZoneEvaluator()
                .attributes(entity, DMAttributes.healthAttributes())
                .context("vanilla_max_health", vanillaMaxHealth)
                .context("health_scale", Config.HEALTH_SCALE.get()),
                ZoneScope.HEALTH, baseBefore);

        if (!Double.isFinite(scaledBase) || scaledBase < 0.0D) {
            scaledBase = vanillaMaxHealth;
        }
        writeTotalValue(baseAttr, baseBefore, scaledBase);

        // ---- 3. 生命值提升 ----
        // 此时 base_health 总值即目标基础生命值，公式直接读它。
        double computed = evaluateZone(ZoneIds.HEALTH_BONUS, new ZoneEvaluator()
                .attributes(entity, DMAttributes.healthAttributes())
                .context("vanilla_max_health", vanillaMaxHealth)
                .context("health_scale", Config.HEALTH_SCALE.get()),
                ZoneScope.HEALTH, baseAttr.getValue());

        if (!Double.isFinite(computed)) {
            return maxAttr.getValue();
        }
        computed = Math.max(1.0D, computed);

        // ---- 以修饰符写回 max_health ----
        writeMaxHealth(maxAttr, computed);
        return maxAttr.getValue();
    }

    /**
     * 让属性总值等于目标值。
     *
     * <p>属性总值 = 基础值 + 各项修饰符。修饰符（镜像来的装备加成）需要保留，
     * 因此只能调整基础值：新基础值 = 旧基础值 + (目标值 − 当前总值)。
     *
     * @param attr    属性实例
     * @param before  调整前的总值
     * @param target  目标总值
     */
    private static void writeTotalValue(AttributeInstance attr, double before, double target) {
        double delta = target - before;
        if (Math.abs(delta) <= EPSILON) {
            return;
        }
        double newBase = attr.getBaseValue() + delta;
        // 基础值不允许为负，否则属性会被钳制导致结果偏差。
        attr.setBaseValue(Math.max(0.0D, newBase));
    }

    /**
     * 把最终最大生命值以修饰符形式叠加到 max_health。
     *
     * @param maxAttr  max_health 属性实例
     * @param computed 目标最大生命值
     */
    private static void writeMaxHealth(AttributeInstance maxAttr, double computed) {
        // 先移除旧修饰符，确保读到的"当前值"不含我们自己的贡献。
        maxAttr.removeModifier(MAX_HEALTH_MODIFIER);

        double delta = computed - maxAttr.getValue();
        if (Math.abs(delta) <= EPSILON) {
            return;
        }

        maxAttr.addOrUpdateTransientModifier(new AttributeModifier(
                MAX_HEALTH_MODIFIER, delta, AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * 求值单个乘区；乘区缺失时返回回退值。
     *
     * @param id        乘区 id
     * @param evaluator 已注入变量的求值器
     * @param scope     作用域
     * @param fallback  乘区缺失时的回退值
     * @return 乘区输出
     */
    private static double evaluateZone(String id,
                                       ZoneEvaluator evaluator,
                                       ZoneScope scope,
                                       double fallback) {
        if (DataRepository.zone(scope, id) == null) {
            return fallback;
        }
        evaluator.evaluate(scope);
        return evaluator.output(id);
    }
}
