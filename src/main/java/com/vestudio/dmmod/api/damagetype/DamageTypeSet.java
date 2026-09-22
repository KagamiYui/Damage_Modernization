package com.vestudio.dmmod.api.damagetype;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 一次伤害的<b>类型标签集合</b>。
 *
 * <h2>为什么是集合而不是枚举</h2>
 * 伤害类型可以<b>同时成立</b>，不是互斥的单选。例如：
 * <ul>
 *   <li>恶魂火球同时是<b>物理</b>与<b>火焰</b>；</li>
 *   <li>被附魔的武器造成物理伤害时，若效果声明「同时视为魔法」，
 *       则<b>物理与魔法两个标签同时为真</b>；
 *       此时物理增伤与魔法增伤会<b>一并生效</b>。</li>
 * </ul>
 * 若把类型建模为枚举，「视为」就只能表达成「不再是原先那个」，
 * 会让原先类型的加成失效，与期望不符。
 *
 * <h2>不可变</h2>
 * 本类是不可变的：每次结算产出一个实例，可通过 {@link #with(ResourceLocation)}
 * 派生新实例。因此可安全地跨方法与跨线程传递。
 */
public final class DamageTypeSet {

    /** 物理伤害标签。 */
    public static final ResourceLocation PHYSICAL =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "physical");

    /** 魔法伤害标签。 */
    public static final ResourceLocation MAGIC =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "magic");

    /** 火焰伤害标签。 */
    public static final ResourceLocation FIRE =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "fire");

    /** 空集合。 */
    public static final DamageTypeSet EMPTY = new DamageTypeSet(Set.of());

    private final Set<ResourceLocation> types;

    private DamageTypeSet(Set<ResourceLocation> types) {
        this.types = types;
    }

    /**
     * 构造一个类型集合。
     *
     * @param types 类型标签
     * @return 不可变的类型集合
     */
    public static DamageTypeSet of(Set<ResourceLocation> types) {
        if (types == null || types.isEmpty()) {
            return EMPTY;
        }
        return new DamageTypeSet(Collections.unmodifiableSet(new LinkedHashSet<>(types)));
    }

    /**
     * 派生一个「再加上某类型」的新集合。
     *
     * <p>本类不可变，因此不会修改原实例。
     * 这正是实现「同时视为」的方式：在原类型之上<b>追加</b>，
     * 而不是替换。
     *
     * @param type 追加的类型
     * @return 新的类型集合
     */
    public DamageTypeSet with(ResourceLocation type) {
        if (type == null || types.contains(type)) {
            return this;
        }
        Set<ResourceLocation> copy = new LinkedHashSet<>(types);
        copy.add(type);
        return new DamageTypeSet(Collections.unmodifiableSet(copy));
    }

    /**
     * {@return 是否包含指定类型}
     *
     * @param type 类型
     */
    public boolean has(ResourceLocation type) {
        return types.contains(type);
    }

    /**
     * {@return 是否包含全部给定类型}
     *
     * @param others 待检查的类型
     */
    public boolean hasAll(Set<ResourceLocation> others) {
        return types.containsAll(others);
    }

    /** {@return 集合中的全部类型} */
    public Set<ResourceLocation> types() {
        return types;
    }

    /** {@return 是否为空} */
    public boolean isEmpty() {
        return types.isEmpty();
    }

    @Override
    public String toString() {
        return types.toString();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DamageTypeSet s && s.types.equals(this.types);
    }

    @Override
    public int hashCode() {
        return types.hashCode();
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /**
     * 从伤害来源解析类型集合。
     *
     * <p>解析过程：
     * <ol>
     *   <li>按标签映射得出基础类型（见 {@link DamageTypeRegistry}）；</li>
     *   <li>依次应用所有已注册的 {@link DamageTypeContributor}，
     *       允许装备、状态效果等<b>追加</b>类型。</li>
     * </ol>
     *
     * <p>追加是「同时视为」语义：原有类型<b>不会</b>被移除，
     * 因此物理增伤与魔法增伤可以同时生效。
     *
     * @param source 伤害来源；可为 null
     * @param victim 受害实体；可为 null
     * @return 类型集合
     */
    public static DamageTypeSet resolve(@Nullable DamageSource source,
                                        @Nullable LivingEntity victim) {
        DamageTypeSet set = DamageTypeRegistry.fromTags(source);

        for (DamageTypeContributor contributor : DamageTypeRegistry.contributors()) {
            try {
                DamageTypeSet updated = contributor.contribute(source, victim, set);
                if (updated != null) {
                    set = updated;
                }
            } catch (Exception e) {
                // 单个贡献者出错不应影响伤害结算。
                com.vestudio.dmmod.DamageModernization.LOGGER.error(
                        "伤害类型贡献者 {} 出错，已跳过",
                        contributor.getClass().getName(), e);
            }
        }

        return set;
    }
}
