package com.vestudio.dmmod.api.damagetype;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/**
 * 伤害类型注册表：定义「哪些伤害类型标签对应哪种伤害类型」，并管理类型贡献者。
 *
 * <h2>标签映射</h2>
 * 每个伤害类型（{@link DamageTypeSet#PHYSICAL} 等）对应一个
 * {@code DamageType} 标签，标签文件列出属于该类型的原版伤害类型：
 * <pre>
 *   data/damagemodernization/tags/damage_type/physical.json
 *   data/damagemodernization/tags/damage_type/magic.json
 * </pre>
 *
 * <p>物理采用<b>排除法</b>：见 {@link #registerExclusive}，
 * 不在排除名单内的伤害一律算物理，因此新增伤害类型默认归入物理。
 *
 * <h2>贡献者</h2>
 * 其他 mod 可注册 {@link DamageTypeContributor}，
 * 在标签判定的基础上<b>追加</b>类型（例如「同时视为魔法」）。
 */
public final class DamageTypeRegistry {

    /** 类型 → 其对应的标签。 */
    private static final Map<ResourceLocation, TagKey<DamageType>> TAG_BY_TYPE =
            new ConcurrentHashMap<>();

    /** 需要按「排除法」判定的类型 → 其排除标签。 */
    private static final Map<ResourceLocation, TagKey<DamageType>> EXCLUSIVE_TAG_BY_TYPE =
            new ConcurrentHashMap<>();

    /** 已注册的类型贡献者，按注册顺序执行。 */
    private static final List<DamageTypeContributor> CONTRIBUTORS =
            new CopyOnWriteArrayList<>();

    private DamageTypeRegistry() {
    }

    /**
     * 注册一个<b>包含式</b>类型标签。
     *
     * <p>伤害类型带有该标签时，即视为拥有对应类型。
     *
     * @param type    类型标识
     * @param tagPath 标签路径
     */
    public static void register(ResourceLocation type, String tagPath) {
        TAG_BY_TYPE.put(type, tagKey(tagPath));
    }

    /**
     * 注册一个<b>排除式</b>类型标签。
     *
     * <p>伤害类型<b>不带</b>该标签时视为拥有对应类型。
     * 用于「默认全都算这一类型」的规则，例如物理。
     *
     * @param type    类型标识
     * @param tagPath 排除标签路径
     */
    public static void registerExclusive(ResourceLocation type, String tagPath) {
        EXCLUSIVE_TAG_BY_TYPE.put(type, tagKey(tagPath));
    }

    /**
     * 注册一个类型贡献者。
     *
     * <p>贡献者在标签判定之后执行，可<b>追加</b>类型。
     *
     * @param contributor 贡献者
     */
    public static void registerContributor(DamageTypeContributor contributor) {
        if (contributor != null) {
            CONTRIBUTORS.add(contributor);
        }
    }

    /**
     * 注销一个类型贡献者。
     *
     * @param contributor 贡献者
     * @return 是否确有移除
     */
    public static boolean unregisterContributor(DamageTypeContributor contributor) {
        return CONTRIBUTORS.remove(contributor);
    }

    /**
     * 按标签判定伤害类型（不含贡献者追加）。
     *
     * @param source 伤害来源；可为 null
     * @return 类型集合
     */
    public static DamageTypeSet fromTags(@Nullable DamageSource source) {
        if (source == null) {
            // 无来源时按物理处理，与「默认之外全归物理」保持一致。
            return DamageTypeSet.EMPTY.with(DamageTypeSet.PHYSICAL);
        }

        List<ResourceLocation> matched = new ArrayList<>();

        // 包含式：带标签即算。
        for (var entry : TAG_BY_TYPE.entrySet()) {
            if (hasTag(source, entry.getValue())) {
                matched.add(entry.getKey());
            }
        }

        // 排除式：不带标签才算。
        for (var entry : EXCLUSIVE_TAG_BY_TYPE.entrySet()) {
            if (!hasTag(source, entry.getValue())) {
                matched.add(entry.getKey());
            }
        }

        DamageTypeSet set = DamageTypeSet.EMPTY;
        for (ResourceLocation type : matched) {
            set = set.with(type);
        }
        return set;
    }

    /**
     * 安全地检查伤害是否带有指定标签。
     *
     * <p>标签未加载等异常情况下返回 false，避免影响伤害结算。
     *
     * @param source 伤害来源
     * @param tag    标签
     * @return 是否带有该标签
     */
    private static boolean hasTag(DamageSource source, TagKey<DamageType> tag) {
        try {
            return source.is(tag);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@return 当前全部类型贡献者}
     */
    public static List<DamageTypeContributor> contributors() {
        return CONTRIBUTORS;
    }

    /**
     * {@return 已注册的全部伤害类型标识}
     *
     * <p>用于注入 {@code has_xxx} 变量时<b>遍历所有类型</b>：
     * 成立的给 1、不成立的给 0。
     * 若只注入成立的那些，公式引用到不成立的变量会抛错，
     * 导致整个乘区被跳过。
     */
    public static java.util.Set<ResourceLocation> knownTypes() {
        java.util.Set<ResourceLocation> out = new java.util.LinkedHashSet<>();
        out.addAll(TAG_BY_TYPE.keySet());
        out.addAll(EXCLUSIVE_TAG_BY_TYPE.keySet());
        return out;
    }

    /**
     * 构造标签键。
     *
     * @param path 标签路径
     * @return 标签键
     */
    private static TagKey<DamageType> tagKey(String path) {
        return TagKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath(DamageModernization.MODID, path));
    }
}
