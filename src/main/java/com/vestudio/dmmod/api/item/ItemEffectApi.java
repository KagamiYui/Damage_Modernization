package com.vestudio.dmmod.api.item;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.vestudio.dmmod.formula.ItemEffectDefinition;
import com.vestudio.dmmod.formula.ItemEffectRepository;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;

/**
 * 给武器、装备与饰品添加效果的公共接口。
 *
 * <h2>用途</h2>
 * 其他 mod 可以用它给物品附加属性加成，无需接触内部实现，
 * 也不必覆盖原版物品数据。
 *
 * <h2>生效时机</h2>
 * 效果在<b>物品属性被查询时</b>生效，也就是「装备/持有时」。
 * 物品躺在背包里不会被查询，因此不会凭空生效。
 *
 * <h2>槽位</h2>
 * 有两个互相独立的维度：
 * <ul>
 *   <li>{@link #slot(ItemEffectDefinition.SlotGroup)} —— 原版槽位；</li>
 *   <li>{@link #curioSlot(String)} —— 饰品栏槽位（需要装有 Curios）。</li>
 * </ul>
 * 默认（什么都不调用）等价于「原版任意槽位 + 全部饰品槽」，兼容性最好。
 * 只想让它出现在饰品栏时用 {@link #curioOnly(String)}。
 *
 * <h2>与药水效果</h2>
 * 药水效果作用于<b>实体属性</b>，伤害公式读的就是实体属性总值，
 * 因此药水本来就生效，无需通过本接口处理。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 给钻石剑 +15% 攻击力与 +10% 暴击率（主手、饰品栏都生效）
 * ItemEffectApi.forItem(Items.DIAMOND_SWORD)
 *     .modifier(DMAttributes.ATTACK_POWER_PERCENT, 0.15)
 *     .modifier(DMAttributes.CRIT_CHANCE, 0.10)
 *     .register();
 *
 * // 给全部剑类 +20% 暴击伤害，仅主手生效
 * ItemEffectApi.forTag(ResourceLocation.withDefaultNamespace("swords"))
 *     .slot(ItemEffectDefinition.SlotGroup.MAINHAND)
 *     .modifier(DMAttributes.CRIT_DAMAGE_BONUS, 0.20)
 *     .register();
 *
 * // 给某枚戒指 +5 攻击力，只在饰品 ring 槽生效
 * ItemEffectApi.forItem(MyItems.RUBY_RING)
 *     .curioOnly("ring")
 *     .modifier(DMAttributes.ATTACK_POWER_FLAT, 5.0)
 *     .register();
 * }</pre>
 */
public final class ItemEffectApi {

    private final ResourceLocation item;
    private final ResourceLocation tag;
    private final List<Pending> pending = new ArrayList<>();

    private ItemEffectDefinition.SlotGroup defaultSlot = ItemEffectDefinition.SlotGroup.ANY;

    /** {@code null} 表示未显式指定，交由 {@link ItemEffectDefinition} 按原版槽位推导。 */
    @Nullable
    private String defaultCurioSlot;

    private ItemEffectApi(ResourceLocation item, ResourceLocation tag) {
        this.item = item;
        this.tag = tag;
    }

    /**
     * 针对指定物品构造。
     *
     * @param item 目标物品
     * @return 构造器
     */
    public static ItemEffectApi forItem(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return new ItemEffectApi(id, null);
    }

    /**
     * 针对指定物品构造。
     *
     * @param itemId 目标物品标识
     * @return 构造器
     */
    public static ItemEffectApi forItem(ResourceLocation itemId) {
        return new ItemEffectApi(itemId, null);
    }

    /**
     * 针对指定物品标签构造（一次覆盖一类物品）。
     *
     * @param tagId 目标物品标签（不含 {@code #}）
     * @return 构造器
     */
    public static ItemEffectApi forTag(ResourceLocation tagId) {
        return new ItemEffectApi(null, tagId);
    }

    /**
     * 设置后续修饰符的默认原版槽位。
     *
     * <p>{@link ItemEffectDefinition.SlotGroup#NONE} 表示不作用于原版槽位，
     * 通常与 {@link #curioSlot(String)} 搭配成「仅饰品」。
     *
     * @param slot 槽位
     * @return 本实例，便于链式调用
     */
    public ItemEffectApi slot(ItemEffectDefinition.SlotGroup slot) {
        this.defaultSlot = slot == null ? ItemEffectDefinition.SlotGroup.ANY : slot;
        return this;
    }

    /**
     * 设置后续修饰符生效的饰品槽（需要装有 Curios）。
     *
     * <p>取值是<b>开放的</b>：饰品槽由数据包定义，因此这里不限定枚举，
     * 常见的有 {@code ring}、{@code necklace}、{@code belt}、{@code charm}、
     * {@code bracelet}、{@code head}、{@code body}、{@code feet}、{@code back}。
     * 可以带 {@code curios:} 前缀，会被自动去掉。
     *
     * <p>还可以传：
     * <ul>
     *   <li>{@link ItemEffectDefinition#CURIO_ANY}（或 {@code "any"}）—— 全部饰品槽；</li>
     *   <li>{@link ItemEffectDefinition#CURIO_NONE}（或 {@code null}）—— 不作用于饰品栏。</li>
     * </ul>
     *
     * @param curioSlot 饰品槽标识
     * @return 本实例，便于链式调用
     */
    public ItemEffectApi curioSlot(@Nullable String curioSlot) {
        this.defaultCurioSlot = curioSlot;
        return this;
    }

    /**
     * 让后续修饰符<b>只</b>在指定饰品槽生效（原版槽位全部不生效）。
     *
     * @param curioSlot 饰品槽标识；{@code null} 表示全部饰品槽
     * @return 本实例，便于链式调用
     */
    public ItemEffectApi curioOnly(@Nullable String curioSlot) {
        this.defaultSlot = ItemEffectDefinition.SlotGroup.NONE;
        this.defaultCurioSlot = curioSlot == null ? ItemEffectDefinition.CURIO_ANY : curioSlot;
        return this;
    }

    /**
     * 添加一个属性修饰符（加算，任意槽位）。
     *
     * @param attribute 属性
     * @param amount    数值
     * @return 本实例
     */
    public ItemEffectApi modifier(Holder<Attribute> attribute, double amount) {
        return add(attribute, amount, AttributeModifier.Operation.ADD_VALUE, defaultSlot);
    }

    /**
     * 添加一个属性修饰符（加算，指定原版槽位）。
     *
     * @param attribute 属性
     * @param amount    数值
     * @param slot      生效槽位
     * @return 本实例
     */
    public ItemEffectApi modifier(Holder<Attribute> attribute,
                                  double amount,
                                  ItemEffectDefinition.SlotGroup slot) {
        return add(attribute, amount, AttributeModifier.Operation.ADD_VALUE, slot);
    }

    /**
     * 添加一个属性修饰符（任意槽位）。
     *
     * @param attribute 属性
     * @param amount    数值
     * @param operation 运算方式
     * @return 本实例
     */
    public ItemEffectApi modifier(Holder<Attribute> attribute,
                                  double amount,
                                  AttributeModifier.Operation operation) {
        return add(attribute, amount, operation, defaultSlot);
    }

    /**
     * 添加一个属性修饰符（完整参数）。
     *
     * @param attribute 属性
     * @param amount    数值
     * @param operation 运算方式
     * @param slot      原版槽位组
     * @return 本实例
     */
    public ItemEffectApi modifier(Holder<Attribute> attribute,
                                  double amount,
                                  AttributeModifier.Operation operation,
                                  EquipmentSlotGroup slot) {
        return add(attribute, amount, operation, slotGroupOf(slot));
    }

    /**
     * 记录一条待注册的修饰符。
     *
     * @param attribute 属性
     * @param amount    数值
     * @param operation 运算方式
     * @param slot      原版槽位；{@code null} 视作任意
     * @return 本实例
     */
    private ItemEffectApi add(Holder<Attribute> attribute,
                              double amount,
                              AttributeModifier.Operation operation,
                              @Nullable ItemEffectDefinition.SlotGroup slot) {
        pending.add(Pending.of(attribute, amount, operation, slot, defaultCurioSlot));
        return this;
    }

    /**
     * 把构造好的全部修饰符注册进物品效果体系。
     *
     * <p>注册后立即生效，且不会因数据文件重载而丢失。
     */
    public void register() {
        for (Pending p : pending) {
            ResourceLocation attributeId = attributeIdOf(p.attribute());
            if (attributeId == null) {
                com.vestudio.dmmod.DamageModernization.LOGGER.error(
                        "物品效果注册失败：属性缺少注册名 {}", p.attribute());
                continue;
            }

            String operationName = switch (p.operation()) {
                case ADD_VALUE -> "add_value";
                case ADD_MULTIPLIED_BASE -> "add_multiplied_base";
                case ADD_MULTIPLIED_TOTAL -> "add_multiplied_total";
            };

            ItemEffectRepository.register(new ItemEffectDefinition(
                    item, tag, attributeId, p.amount(), operationName,
                    p.slot(), p.curioSlot(), "由其他 mod 通过 API 注册"));
        }
    }

    /**
     * {@return 属性对应的注册名；取不到时返回 null}
     *
     * @param attribute 属性
     */
    @Nullable
    private static ResourceLocation attributeIdOf(Holder<Attribute> attribute) {
        return attribute.unwrapKey().map(key -> key.location()).orElse(null);
    }

    /**
     * {@return 原版槽位组对应的槽位枚举}
     *
     * @param slot 原版槽位组；{@code null} 视作任意
     */
    private static ItemEffectDefinition.SlotGroup slotGroupOf(@Nullable EquipmentSlotGroup slot) {
        if (slot == null) {
            return ItemEffectDefinition.SlotGroup.ANY;
        }
        for (ItemEffectDefinition.SlotGroup s : ItemEffectDefinition.SlotGroup.values()) {
            if (s.toVanilla() == slot) {
                return s;
            }
        }
        return ItemEffectDefinition.SlotGroup.ANY;
    }

    /**
     * 待注册的修饰符。
     *
     * @param attribute 属性
     * @param amount    数值
     * @param operation 运算方式
     * @param slot      原版槽位
     * @param curioSlot 饰品槽
     */
    private record Pending(Holder<Attribute> attribute,
                           double amount,
                           AttributeModifier.Operation operation,
                           ItemEffectDefinition.SlotGroup slot,
                           @Nullable String curioSlot) {

        /**
         * 构造时收敛 {@code null} 槽位。
         *
         * @param attribute 属性
         * @param amount    数值
         * @param operation 运算方式
         * @param slot      原版槽位
         * @param curioSlot 饰品槽
         * @return 待注册项
         */
        static Pending of(Holder<Attribute> attribute,
                          double amount,
                          AttributeModifier.Operation operation,
                          @Nullable ItemEffectDefinition.SlotGroup slot,
                          @Nullable String curioSlot) {
            return new Pending(attribute, amount, operation,
                    slot == null ? ItemEffectDefinition.SlotGroup.ANY : slot, curioSlot);
        }
    }
}
