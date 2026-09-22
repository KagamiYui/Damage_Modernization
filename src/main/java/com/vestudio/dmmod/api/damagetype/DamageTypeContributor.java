package com.vestudio.dmmod.api.damagetype;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 伤害类型贡献者：在不改变原有类型的前提下，为一次伤害<b>追加</b>类型。
 *
 * <h2>用途</h2>
 * 用于实现「某装备/效果让伤害同时带有另一种类型」这类需求。例如：
 * <ul>
 *   <li>某把武器「造成物理伤害时同时视为魔法伤害」；</li>
 *   <li>某状态效果让所有伤害附带火焰属性。</li>
 * </ul>
 *
 * <h2>关键语义：追加，不是替换</h2>
 * 返回值应当是<b>在原集合之上追加</b>得到的新集合，
 * 通常写作 {@code set.with(某类型)}。
 *
 * <p>正因为是追加，原类型<b>不会丢失</b>——
 * 于是物理增伤与魔法增伤可以<b>同时生效</b>，
 * 这正是「同时视为」所期望的行为。
 *
 * <p>若返回 {@code null}，表示本次不做任何改动。
 *
 * <h2>注册</h2>
 * <pre>{@code
 * DamageTypeRegistry.registerContributor((source, victim, types) -> {
 *     if (victim != null && victim.hasEffect(MobEffects.…)) {
 *         return types.with(DamageTypeSet.MAGIC);
 *     }
 *     return null;
 * });
 * }</pre>
 *
 * <h2>示例：某装备让物理伤害同时视为魔法</h2>
 * <pre>{@code
 * DamageTypeRegistry.registerContributor((source, victim, types) -> {
 *     if (types.has(DamageTypeSet.PHYSICAL) && hasArcaneWeapon(source)) {
 *         return types.with(DamageTypeSet.MAGIC);
 *     }
 *     return null;
 * });
 * }</pre>
 */
@FunctionalInterface
public interface DamageTypeContributor {

    /**
     * 为一次伤害追加类型。
     *
     * @param source 伤害来源；可能为 null
     * @param victim 受害实体；可能为 null
     * @param current 当前已有的类型集合
     * @return 追加后的新集合；返回 {@code null} 表示不改动
     */
    @Nullable
    DamageTypeSet contribute(@Nullable DamageSource source,
                             @Nullable LivingEntity victim,
                             DamageTypeSet current);

    /**
     * 便捷方法：在满足条件时追加一个类型。
     *
     * @param type      要追加的类型
     * @param condition 条件
     * @return 贡献者
     */
    static DamageTypeContributor when(java.util.function.BiPredicate<DamageSource, LivingEntity> condition,
                                      ResourceLocation type) {
        return (source, victim, current) ->
                condition.test(source, victim) ? current.with(type) : null;
    }
}
