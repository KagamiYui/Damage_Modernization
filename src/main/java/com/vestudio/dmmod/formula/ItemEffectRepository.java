package com.vestudio.dmmod.formula;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 物品效果仓库：存放「给某个物品追加属性修饰符」的定义，并按物品查询。
 *
 * <h2>匹配方式</h2>
 * 支持两种：
 * <ul>
 *   <li>按<b>物品</b>精确匹配（{@code minecraft:diamond_sword}）；</li>
 *   <li>按<b>标签</b>匹配（{@code #minecraft:swords}），一次覆盖一类物品。</li>
 * </ul>
 *
 * <h2>两个来源</h2>
 * <ol>
 *   <li><b>数据文件</b>：{@code itemEffects} 数组，见 {@link #install(List)}；</li>
 *   <li><b>其他 mod 的运行时注册</b>：见 {@link #register(ItemEffectDefinition)}
 *       与 {@link com.vestudio.dmmod.api.item.ItemEffectApi}。</li>
 * </ol>
 * 两者共存：数据文件重建时会保留运行时注册的部分，不会被覆盖掉。
 *
 * <p>不匹配任何定义时开销极小（两次 map 查询），因为该方法会在
 * 每次物品属性查询时被调用。
 */
public final class ItemEffectRepository {

    /** 物品 → 其效果列表。 */
    private static volatile Map<ResourceLocation, List<ItemEffectDefinition>> byItem = Map.of();

    /** 物品标签 → 其效果列表。 */
    private static volatile Map<ResourceLocation, List<ItemEffectDefinition>> byTag = Map.of();

    /** 来自数据文件的定义（重建时会被整体替换）。 */
    private static volatile List<ItemEffectDefinition> fromData = List.of();

    /** 来自其他 mod 运行时注册的定义（重建数据时保留）。 */
    private static final List<ItemEffectDefinition> fromCode = new CopyOnWriteArrayList<>();

    private ItemEffectRepository() {
    }

    /**
     * 安装数据文件里的定义。
     *
     * <p>只替换数据文件来源的部分，<b>运行时注册的不会被清除</b>，
     * 因此其他 mod 的注册不会因为数据重载而丢失。
     *
     * @param definitions 来自数据文件的物品效果定义
     */
    public static void install(List<ItemEffectDefinition> definitions) {
        fromData = List.copyOf(definitions);
        rebuild();
    }

    /**
     * 运行时注册一条物品效果（供其他 mod 调用）。
     *
     * <p>注册后立即生效，且不会因为数据文件重载而丢失。
     *
     * @param definition 物品效果定义
     */
    public static void register(ItemEffectDefinition definition) {
        if (definition == null) {
            return;
        }
        List<String> errors = definition.validate();
        if (!errors.isEmpty()) {
            DamageModernization.LOGGER.error(
                    "物品效果注册被拒绝 {}: {}", definition.attribute(),
                    String.join("; ", errors));
            return;
        }
        fromCode.add(definition);
        rebuild();
    }

    /**
     * 注销一条运行时注册的物品效果。
     *
     * @param definition 注册时使用的同一实例
     * @return 是否确有移除
     */
    public static boolean unregister(ItemEffectDefinition definition) {
        boolean removed = fromCode.remove(definition);
        if (removed) {
            rebuild();
        }
        return removed;
    }

    /**
     * 清空全部运行时注册（主要供测试使用）。
     */
    public static void clearRuntimeRegistrations() {
        fromCode.clear();
        rebuild();
    }

    /**
     * 按当前的两类来源重建索引。
     */
    private static void rebuild() {
        Map<ResourceLocation, List<ItemEffectDefinition>> items = new LinkedHashMap<>();
        Map<ResourceLocation, List<ItemEffectDefinition>> tags = new LinkedHashMap<>();

        for (ItemEffectDefinition def : all()) {
            if (def.usesTag()) {
                tags.computeIfAbsent(def.tag(), k -> new ArrayList<>()).add(def);
            } else {
                items.computeIfAbsent(def.item(), k -> new ArrayList<>()).add(def);
            }
        }

        byItem = Map.copyOf(items);
        byTag = Map.copyOf(tags);

        DamageModernization.LOGGER.info(
                "物品效果已更新: 按物品 {} 项, 按标签 {} 项（其中运行时注册 {} 项）",
                items.size(), tags.size(), fromCode.size());
    }

    /**
     * {@return 数据文件与运行时代码注册的全部定义}
     */
    private static List<ItemEffectDefinition> all() {
        if (fromCode.isEmpty()) {
            return fromData;
        }
        if (fromData.isEmpty()) {
            return List.copyOf(fromCode);
        }
        List<ItemEffectDefinition> merged = new ArrayList<>(fromData);
        merged.addAll(fromCode);
        return merged;
    }

    /**
     * {@return 该物品应生效的全部效果；没有则返回空列表}
     *
     * @param stack 物品
     */
    public static List<ItemEffectDefinition> forStack(ItemStack stack) {
        if (stack.isEmpty() || (byItem.isEmpty() && byTag.isEmpty())) {
            return List.of();
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        List<ItemEffectDefinition> direct = byItem.get(itemId);

        List<ItemEffectDefinition> fromTags = null;
        for (var entry : byTag.entrySet()) {
            if (stack.is(TagKey.create(Registries.ITEM, entry.getKey()))) {
                if (fromTags == null) {
                    fromTags = new ArrayList<>();
                }
                fromTags.addAll(entry.getValue());
            }
        }

        if (direct == null && fromTags == null) {
            return List.of();
        }
        if (direct == null) {
            return fromTags;
        }
        if (fromTags == null) {
            return direct;
        }

        List<ItemEffectDefinition> merged = new ArrayList<>(direct);
        merged.addAll(fromTags);
        return merged;
    }

    /**
     * {@return 是否有任何已加载的物品效果}
     */
    public static boolean isEmpty() {
        return byItem.isEmpty() && byTag.isEmpty();
    }

    /**
     * 为效果生成稳定的修饰符 id。
     *
     * <p>id 需要唯一，否则后一个会覆盖前一个。
     * 这里用「属性路径 + 序号」构成，同一物品的多条同类效果也能共存。
     *
     * @param def   效果定义
     * @param index 该物品内的序号
     * @return 修饰符 id
     */
    public static ResourceLocation modifierId(ItemEffectDefinition def, int index) {
        String path = def.attribute().getPath() + "_" + index;
        return ResourceLocation.fromNamespaceAndPath(DamageModernization.MODID, path);
    }
}
