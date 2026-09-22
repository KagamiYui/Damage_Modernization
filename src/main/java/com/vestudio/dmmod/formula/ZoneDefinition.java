package com.vestudio.dmmod.formula;

import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 一个乘区的定义。
 *
 * <p>乘区是<b>通用的公式单元</b>：它从上下文读取变量，算出一个乘数，
 * 由所属体系决定这些乘数如何组合（伤害体系是连乘，生命值体系是依次代入）。
 *
 * @param scope     所属体系（伤害 / 生命值）
 * @param id        乘区路径，如 {@code attack_power}
 * @param enabled   是否启用；关闭时该乘区按中性值处理
 * @param priority  执行顺序，数值越小越先执行
 * @param formula   公式文本
 * @param requires  变量白名单：公式中允许引用的变量名
 * @param scale     全局缩放系数，作用在公式输出之上（1.0 表示不缩放）
 * @param comment   说明文本，仅供阅读
 */
public record ZoneDefinition(
        ZoneScope scope,
        String id,
        boolean enabled,
        int priority,
        String formula,
        List<String> requires,
        double scale,
        String comment) {

    /** 默认的全局缩放系数。 */
    public static final double DEFAULT_SCALE = 1.0D;

    /** 默认优先级。 */
    public static final int DEFAULT_PRIORITY = 500;

    /**
     * 供数据文件解析与序列化使用的编解码器。
     *
     * <p>字段名与 JSON 中的键一致；缺省字段使用默认值，
     * 这样数据文件可以只写关心的部分。
     */
    public static final Codec<ZoneDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("scope")
                    .forGetter(z -> z.scope().id()),
            Codec.STRING.fieldOf("id")
                    .forGetter(ZoneDefinition::id),
            Codec.BOOL.optionalFieldOf("enabled", true)
                    .forGetter(ZoneDefinition::enabled),
            Codec.INT.optionalFieldOf("priority", DEFAULT_PRIORITY)
                    .forGetter(ZoneDefinition::priority),
            Codec.STRING.fieldOf("formula")
                    .forGetter(ZoneDefinition::formula),
            Codec.STRING.listOf().optionalFieldOf("requires", List.of())
                    .forGetter(ZoneDefinition::requires),
            Codec.DOUBLE.optionalFieldOf("scale", DEFAULT_SCALE)
                    .forGetter(ZoneDefinition::scale),
            Codec.STRING.optionalFieldOf("comment", "")
                    .forGetter(ZoneDefinition::comment)
    ).apply(instance, (scopeId, id, enabled, priority, formula, requires, scale, comment) ->
            new ZoneDefinition(
                    ZoneScope.byId(scopeId), id, enabled, priority,
                    formula, requires, scale, comment)));

    /**
     * 由数据文件字段构造定义（宽松解析入口）。
     *
     * @param scopeId  作用域标识
     * @param id       乘区路径
     * @param enabled  是否启用
     * @param priority 执行顺序
     * @param formula  公式文本
     * @param requires 变量白名单
     * @param scale    全局缩放
     * @param comment  说明文本
     * @return 构造出的定义
     * @throws FormulaException 作用域未知时抛出
     */
    public static ZoneDefinition of(String scopeId,
                                    String id,
                                    boolean enabled,
                                    int priority,
                                    String formula,
                                    List<String> requires,
                                    double scale,
                                    String comment) {
        return new ZoneDefinition(ZoneScope.byId(scopeId), id, enabled, priority,
                formula, requires, scale, comment);
    }

    /**
     * {@return 完整的资源位置（含作用域前缀，如 {@code damagemodernization:damage/attack_power}）}
     */
    public net.minecraft.resources.ResourceLocation key() {
        return scope.key(id);
    }

    /**
     * 校验本定义是否自洽。
     *
     * <p>检查公式能否解析、以及是否引用了白名单之外的变量。
     * 校验失败的定义不会被加载，避免运行期才暴露问题。
     *
     * @return 错误说明列表；为空表示通过
     */
    public List<String> validate() {
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();

        if (id == null || id.isBlank()) {
            errors.add("id 不能为空");
        }
        if (formula == null || formula.isBlank()) {
            errors.add("formula 不能为空");
            return errors;
        }
        if (!Double.isFinite(scale)) {
            errors.add("scale 必须是有限数值");
        }

        FormulaEngine.Expression parsed;
        try {
            parsed = FormulaEngine.parse(formula);
        } catch (RuntimeException e) {
            errors.add("公式无法解析: " + e.getMessage());
            return errors;
        }

        // 白名单校验：公式引用的变量必须已声明。
        Set<String> declared = Set.copyOf(requires == null ? List.of() : requires);
        for (String used : FormulaEngine.collectVariables(parsed)) {
            if (!declared.contains(used)) {
                errors.add("公式引用了未在 requires 中声明的变量: " + used);
            }
        }

        return errors;
    }
}
