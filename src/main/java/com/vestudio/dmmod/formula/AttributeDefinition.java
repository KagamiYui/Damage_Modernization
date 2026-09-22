package com.vestudio.dmmod.formula;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个属性的定义。
 *
 * <p>把属性的默认值、取值范围与显示方式从代码搬到数据文件，
 * 使模组包无需重新编译即可调整。
 *
 * @param id        属性的资源位置
 * @param hasDefault 是否显式给出默认值
 * @param defaultValue 默认值（{@code hasDefault} 为 false 时忽略）
 * @param vanillaSource 默认值取自原版某个属性时填写（如 {@code minecraft:max_health}）；
 *                      用于「按生物类型取原版数值」的属性，此时不需要固定默认值
 * @param min       最小值
 * @param max       最大值
 * @param display   显示方式：{@code plain} 或 {@code percent}
 * @param sentiment 色调：{@code positive} / {@code negative} / {@code neutral}
 * @param syncable  是否同步到客户端
 * @param comment   说明文本
 */
public record AttributeDefinition(
        ResourceLocation id,
        boolean hasDefault,
        double defaultValue,
        ResourceLocation vanillaSource,
        double min,
        double max,
        Display display,
        Sentiment sentiment,
        boolean syncable,
        String comment) {

    /** 显示方式。 */
    public enum Display {
        /** 直接显示数值。 */
        PLAIN,
        /** 以百分比显示（0.05 → 5%）。 */
        PERCENT
    }

    /** 色调，影响 tooltip 配色。 */
    public enum Sentiment {
        /** 越高越好。 */
        POSITIVE,
        /** 越低越好（例如受到的伤害）。 */
        NEGATIVE,
        /** 中性。 */
        NEUTRAL
    }

    /**
     * 数据文件使用的编解码器。
     *
     * <p>{@code default} 与 {@code vanillaSource} 二选一：
     * 前者是固定数值，后者表示「按生物类型取原版该属性的数值」。
     */
    public static final Codec<AttributeDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id")
                    .forGetter(AttributeDefinition::id),
            Codec.DOUBLE.optionalFieldOf("default", Double.NaN)
                    .forGetter(a -> a.hasDefault() ? a.defaultValue() : Double.NaN),
            ResourceLocation.CODEC.optionalFieldOf("vanillaSource", null)
                    .forGetter(a -> a.vanillaSource()),
            Codec.DOUBLE.optionalFieldOf("min", 0.0D)
                    .forGetter(AttributeDefinition::min),
            Codec.DOUBLE.optionalFieldOf("max", 1_000_000.0D)
                    .forGetter(AttributeDefinition::max),
            Codec.STRING.optionalFieldOf("display", "plain")
                    .forGetter(a -> a.display().name().toLowerCase(java.util.Locale.ROOT)),
            Codec.STRING.optionalFieldOf("sentiment", "positive")
                    .forGetter(a -> a.sentiment().name().toLowerCase(java.util.Locale.ROOT)),
            Codec.BOOL.optionalFieldOf("syncable", true)
                    .forGetter(AttributeDefinition::syncable),
            Codec.STRING.optionalFieldOf("comment", "")
                    .forGetter(AttributeDefinition::comment)
    ).apply(instance, (id, def, vanillaSource, min, max, display, sentiment, syncable, comment) -> {
        boolean hasDefault = def != null && !Double.isNaN(def);
        return new AttributeDefinition(
                id, hasDefault, hasDefault ? def : Double.NaN, vanillaSource,
                min, max,
                parseDisplay(display), parseSentiment(sentiment),
                syncable, comment);
    }));

    /**
     * 由数据文件字段构造定义。
     *
     * <p>提供显式构造入口，便于加载器做宽松解析
     * （忽略未知字段、给出清晰报错），而不是依赖严格的编解码器。
     *
     * @param id            属性标识
     * @param defaultValue  固定默认值；未提供时传 null
     * @param vanillaSource 原版来源属性；未提供时传 null
     * @param min           最小值
     * @param max           最大值
     * @param displayRaw    显示方式文本
     * @param sentimentRaw  色调文本
     * @param syncable      是否同步
     * @param comment       说明文本
     * @return 构造出的定义
     */
    public static AttributeDefinition of(ResourceLocation id,
                                         Double defaultValue,
                                         ResourceLocation vanillaSource,
                                         double min,
                                         double max,
                                         String displayRaw,
                                         String sentimentRaw,
                                         boolean syncable,
                                         String comment) {
        boolean hasDefault = defaultValue != null && !Double.isNaN(defaultValue);
        return new AttributeDefinition(
                id, hasDefault, hasDefault ? defaultValue : Double.NaN, vanillaSource,
                min, max, parseDisplay(displayRaw), parseSentiment(sentimentRaw),
                syncable, comment);
    }

    /**
     * 解析显示方式。
     *
     * @param raw 文本
     * @return 显示方式
     */
    private static Display parseDisplay(String raw) {
        return "percent".equalsIgnoreCase(raw) ? Display.PERCENT : Display.PLAIN;
    }

    /**
     * 解析色调。
     *
     * @param raw 文本
     * @return 色调
     */
    private static Sentiment parseSentiment(String raw) {
        for (Sentiment s : Sentiment.values()) {
            if (s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        return Sentiment.POSITIVE;
    }

    /**
     * 校验定义是否自洽。
     *
     * @return 错误说明列表；为空表示通过
     */
    public List<String> validate() {
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();

        if (id == null) {
            errors.add("id 不能为空");
            return errors;
        }
        if (min > max) {
            errors.add("min 不能大于 max");
        }
        if (hasDefault && (defaultValue < min || defaultValue > max)) {
            errors.add("default 超出 [min, max] 范围");
        }
        if (!hasDefault && vanillaSource == null) {
            // 既没有固定默认值也没有原版来源时，属性会退化为 min，
            // 这通常不是本意，因此视为错误。
            errors.add("必须提供 default 或 vanillaSource 之一");
        }
        return errors;
    }
}
