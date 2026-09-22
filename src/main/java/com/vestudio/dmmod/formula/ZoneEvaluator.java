package com.vestudio.dmmod.formula;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/**
 * 按作用域求值一个体系下的全部乘区。
 *
 * <h2>变量来源</h2>
 * 公式可用的变量取自三处，按优先级从低到高：
 * <ol>
 *   <li><b>属性值</b>：属性 ID 去掉命名空间后的名字，如 {@code base_attack_power}。
 *       从攻击者与受害者身上读取。</li>
 *   <li><b>上下文变量</b>：由调用方注入的当次结算信息，
 *       如 {@code is_critical}、{@code vanilla_max_health}。</li>
 *   <li><b>输出变量</b>：本体系内<b>前面</b>已求值乘区的输出，如 {@code base_health}。</li>
 * </ol>
 *
 * <p>本类不使用任何静态可变状态，每个实例针对一次结算创建，
 * 因此不存在跨实体或跨帧串值的问题。
 */
public final class ZoneEvaluator {

    /** 变量名 → 数值。 */
    private final Map<String, Double> variables = new HashMap<>();

    /** 乘区 id → 输出值。 */
    private final Map<String, Double> outputs = new HashMap<>();

    /** 已解析的公式缓存，避免同一乘区反复解析。 */
    private final Map<ResourceLocation, FormulaEngine.Expression> parsed = new HashMap<>();

    /**
     * 注入一个上下文变量。
     *
     * @param name  变量名
     * @param value 数值
     * @return 本实例，便于链式调用
     */
    public ZoneEvaluator context(String name, double value) {
        variables.put(name, value);
        return this;
    }

    /**
     * 从实体读取若干属性并注入为变量。
     *
     * <p>变量名 = 属性 ID 的路径部分（见 {@code DMAttributes#variableName}）。
     * 属性不存在时注入 0，避免公式因缺变量而报错。
     *
     * @param entity     实体；可为 null（此时全部注入 0）
     * @param attributes 属性列表
     * @return 本实例，便于链式调用
     */
    public ZoneEvaluator attributes(LivingEntity entity, List<Holder<Attribute>> attributes) {
        for (Holder<Attribute> attribute : attributes) {
            variables.put(com.vestudio.dmmod.api.DMAttributes.variableName(attribute),
                    attributeValue(entity, attribute));
        }
        return this;
    }

    /**
     * 求值指定作用域下的全部乘区，并返回各乘区的输出。
     *
     * <p>乘区按 {@code priority} 升序执行；每个乘区的输出立即注入变量表，
     * 供后续乘区通过同名变量读取。
     *
     * <p><b>失败隔离</b>：单个乘区出错（公式无法解析、变量缺失、求值异常）
     * 只跳过该乘区并记录日志，不影响其余乘区。
     *
     * @param scope 作用域
     * @return 乘区 id → 输出值；返回的是不可变副本
     */
    public Map<String, Double> evaluate(ZoneScope scope) {
        List<ZoneDefinition> zones = DataRepository.zonesOf(scope);

        for (ZoneDefinition zone : zones) {
            double value = evaluateZone(zone);
            outputs.put(zone.id(), value);
            // 输出立即可被后续乘区引用。
            variables.put(zone.id(), value);
        }

        return Map.copyOf(outputs);
    }

    /**
     * 求值单个乘区。
     *
     * @param zone 乘区定义
     * @return 乘区输出；出错时返回中性值 1.0
     */
    private double evaluateZone(ZoneDefinition zone) {
        FormulaEngine.Expression expression = parsed.get(zone.key());

        if (expression == null) {
            try {
                expression = FormulaEngine.parse(zone.formula());
                parsed.put(zone.key(), expression);
            } catch (RuntimeException e) {
                DamageModernization.LOGGER.error(
                        "乘区 {} 的公式无法解析，已跳过: {}", zone.key(), e.getMessage());
                return neutralValue();
            }
        }

        try {
            double raw = expression.evaluate(name -> {
                Double v = variables.get(name);
                if (v == null) {
                    throw new FormulaEngine.FormulaException("变量未提供: " + name);
                }
                return v;
            });

            // 全局缩放作用在公式输出之上。
            double result = raw * zone.scale();

            if (!Double.isFinite(result)) {
                DamageModernization.LOGGER.error(
                        "乘区 {} 求值结果非法({}), 已按中性值处理", zone.key(), result);
                return neutralValue();
            }

            return result;
        } catch (RuntimeException e) {
            DamageModernization.LOGGER.error(
                    "乘区 {} 求值失败，已跳过: {}", zone.key(), e.getMessage());
            return neutralValue();
        }
    }

    /**
     * {@return 乘区出错时的中性值}
     *
     * <p>伤害体系与生命值体系都是「乘算」，中性值为 1.0。
     */
    private static double neutralValue() {
        return 1.0D;
    }

    /**
     * {@return 指定乘区的输出；未求值时返回 0}
     *
     * @param id 乘区 id
     */
    public double output(String id) {
        return outputs.getOrDefault(id, 0.0D);
    }

    /**
     * 读取属性值；属性不存在时返回 0。
     *
     * @param entity    实体
     * @param attribute 属性
     * @return 属性值
     */
    private static double attributeValue(LivingEntity entity, Holder<Attribute> attribute) {
        if (entity == null) {
            return 0.0D;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }
}
