package com.vestudio.dmmod.formula;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 数据文件的根结构。
 *
 * <p>一个数据文件可以同时声明属性定义与乘区定义，
 * 这样模组包把相关配置放在一起更直观；也可以拆成多个文件分别维护。
 *
 * <pre>{@code
 * {
 *   "attributes": [ ... ],
 *   "zones": [ ... ]
 * }
 * }</pre>
 *
 * @param attributes 属性定义
 * @param zones      乘区定义
 */
public record ModData(
        List<AttributeDefinition> attributes,
        List<ZoneDefinition> zones) {

    /** 空数据，用作默认值。 */
    public static final ModData EMPTY = new ModData(List.of(), List.of());

    /** 数据文件使用的编解码器。 */
    public static final Codec<ModData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            AttributeDefinition.CODEC.listOf().optionalFieldOf("attributes", List.of())
                    .forGetter(ModData::attributes),
            ZoneDefinition.CODEC.listOf().optionalFieldOf("zones", List.of())
                    .forGetter(ModData::zones)
    ).apply(instance, ModData::new));
}
