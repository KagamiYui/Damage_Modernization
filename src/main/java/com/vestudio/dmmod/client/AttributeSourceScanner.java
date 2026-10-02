package com.vestudio.dmmod.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * 属性来源分析：把一个属性的数值拆成「哪来的」。
 *
 * <h2>精度上限</h2>
 * Minecraft <b>不记录</b>修饰符的来源——{@code AttributeInstance} 上只剩
 * {@code (id, amount, operation)} 三件套。所以这里做的是<b>拿 id 反查</b>，不是查询：
 * <ol>
 *   <li><b>装备</b>：遍历各装备槽，读物品自带的 {@code ATTRIBUTE_MODIFIERS}，
 *       用 {@code modifier.id()} 匹配——原版装备的 id 是槽位级的
 *       （{@code minecraft:armor.helmet} 这种），所以能精确到「哪一件」；</li>
 *   <li><b>其他来源</b>：匹配不上就按命名空间归属到对应的 mod；</li>
 *   <li><b>原版基础值</b>：单独列一条，它不是修饰符。</li>
 * </ol>
 *
 * <p>拿不到 mod 自定义的显示名——{@code ItemAttributeModifiers.Entry} 在 1.21.1
 * 只有 {@code (attribute, modifier, slot)}，{@code display} 字段是 1.21.2 才加的。
 */
public final class AttributeSourceScanner {

    /**
     * 会被扫描来源的属性。
     *
     * <p>前四项是<b>原版属性</b>——本 mod 不再自建「基础值属性」去镜像它们，
     * 加成以修饰符形式挂上去，因此来源就在那里拆；
     * 后两项是本 mod 自己的攻击力增量属性，外部 mod 搬进来的
     * 「近战攻击力 / 近战伤害倍率」会落在上面，同样需要能追溯出处。
     */
    private static final List<Holder<Attribute>> TRACKED = List.of(
            Attributes.MAX_HEALTH,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.ATTACK_DAMAGE,
            DMAttributes.ATTACK_POWER_PERCENT,
            DMAttributes.ATTACK_POWER_FLAT);

    private AttributeSourceScanner() {
    }

    /**
     * 一条来源。
     *
     * @param owner     来源名（装备名 / mod 名 / 「原版基础值」）
     * @param amount    数值
     * @param operation 运算方式
     * @param vanilla   是否属于「基础值」（原版来源）
     */
    public record Source(String owner, double amount, AttributeModifier.Operation operation, boolean vanilla) {
    }

    /**
     * 一个属性的完整来源构成。
     *
     * @param name    属性显示名
     * @param total   当前总值
     * @param base    原版基础值
     * @param sources 修饰符来源
     */
    public record AttributeSources(String name, double total, double base, List<Source> sources) {
    }

    /**
     * 扫描全部被追踪的属性。
     *
     * @param player 本地玩家
     * @return 每个属性一项
     */
    public static List<AttributeSources> scan(LocalPlayer player) {
        Map<ResourceLocation, String> owners = ownerIndex(player);

        List<AttributeSources> result = new ArrayList<>();
        for (Holder<Attribute> attribute : TRACKED) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }

            List<Source> sources = new ArrayList<>();
            for (AttributeModifier modifier : instance.getModifiers()) {
                String owner = owners.get(modifier.id());
                boolean vanilla = modifier.id().getNamespace().equals("minecraft");
                if (owner == null) {
                    owner = namespaceLabel(modifier.id().getNamespace());
                }
                sources.add(new Source(owner, modifier.amount(), modifier.operation(), vanilla));
            }

            result.add(new AttributeSources(
                    name(attribute), instance.getValue(), instance.getBaseValue(), sources));
        }
        return result;
    }

    /**
     * 建立「修饰符 id → 来源物品名」的索引。
     *
     * @param player 本地玩家
     * @return 索引
     */
    private static Map<ResourceLocation, String> ownerIndex(LocalPlayer player) {
        Map<ResourceLocation, String> map = new HashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String label = stack.getHoverName().getString();
            for (var entry : stack.getAttributeModifiers().modifiers()) {
                map.putIfAbsent(entry.modifier().id(), label);
            }
        }
        return map;
    }

    /**
     * 把命名空间翻译成可读的来源名。
     *
     * @param namespace 命名空间
     * @return 来源名
     */
    private static String namespaceLabel(String namespace) {
        if ("minecraft".equals(namespace)) {
            return "原版";
        }
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    /**
     * {@return 属性的显示名}
     *
     * @param attribute 属性
     */
    private static String name(Holder<Attribute> attribute) {
        return Component.translatable(attribute.value().getDescriptionId()).getString();
    }

    /**
     * {@return 该属性是否会被扫描来源}
     *
     * @param attribute 属性
     */
    public static boolean isVanillaBacked(Holder<Attribute> attribute) {
        return TRACKED.contains(attribute);
    }

    /**
     * {@return 对应的原版属性，供对照显示}
     *
     * <p>追踪的本就是原版属性，因此原样返回。
     *
     * @param attribute 属性
     */
    public static Holder<Attribute> vanillaOf(Holder<Attribute> attribute) {
        return attribute;
    }
}
