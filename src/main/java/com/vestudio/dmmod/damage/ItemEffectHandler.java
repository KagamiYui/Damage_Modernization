package com.vestudio.dmmod.damage;

import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.formula.ItemEffectDefinition;
import com.vestudio.dmmod.formula.ItemEffectRepository;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;

/**
 * 把数据文件里定义的「物品效果」追加到物品的属性上。
 *
 * <h2>为什么用这个事件</h2>
 * {@link ItemAttributeModifierEvent} 在<b>查询物品属性时</b>触发，
 * 这正好是「装备/持有才生效」的语义——
 * 物品躺在背包里不会被查询，因此效果不会凭空生效。
 *
 * <p>它也是给武器、护甲乃至饰品类物品附加属性的统一入口：
 * 只要物品的属性被读取，本监听器就有机会追加内容。
 *
 * <h2>槽位与饰品栏</h2>
 * 修饰符以 {@code slot} 指定的槽位组添加，默认 {@code any}。
 * 选 {@code any} 时，无论物品在<b>主手、护甲还是饰品栏</b>，
 * 属性都会生效——这是与饰品类 mod 兼容的关键。
 *
 * <h2>与药水效果的关系</h2>
 * 药水效果作用于<b>实体属性</b>，而伤害公式读的就是实体属性总值，
 * 因此药水本来就会生效，无需在此处理。
 */
@EventBusSubscriber(modid = DamageModernization.MODID)
public final class ItemEffectHandler {

    private ItemEffectHandler() {
    }

    /**
     * 查询物品属性时追加数据文件里定义的效果。
     *
     * @param event 物品属性事件
     */
    @SubscribeEvent
    public static void onItemAttributeModifier(ItemAttributeModifierEvent event) {
        if (ItemEffectRepository.isEmpty()) {
            return;
        }

        ItemStack stack = event.getItemStack();
        List<ItemEffectDefinition> effects = ItemEffectRepository.forStack(stack);
        if (effects.isEmpty()) {
            return;
        }

        int index = 0;
        for (ItemEffectDefinition def : effects) {
            try {
                applyEffect(event, def, index++);
            } catch (Exception e) {
                // 单条效果出错不应影响其他效果或物品本身的属性。
                DamageModernization.LOGGER.error(
                        "物品效果应用失败: item={} attribute={}",
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        def.attribute(), e);
            }
        }
    }

    /**
     * 应用单条效果。
     *
     * @param event 物品属性事件
     * @param def   效果定义
     * @param index 该物品内的序号，用于生成唯一 id
     */
    private static void applyEffect(ItemAttributeModifierEvent event,
                                    ItemEffectDefinition def,
                                    int index) {
        // slot = none 表示只作用于饰品栏，这里无需处理。
        if (!def.appliesToVanilla()) {
            return;
        }

        // 属性必须已存在，否则跳过——数据文件写错名字时给出明确日志。
        var attributeHolder = BuiltInRegistries.ATTRIBUTE.getHolder(def.attribute());
        if (attributeHolder.isEmpty()) {
            DamageModernization.LOGGER.error(
                    "物品效果引用了不存在的属性，已跳过: {}", def.attribute());
            return;
        }

        Holder<Attribute> attribute = attributeHolder.get();

        AttributeModifier modifier = new AttributeModifier(
                ItemEffectRepository.modifierId(def, index),
                def.amount(),
                def.operationValue());

        // addModifier 是追加语义，不会覆盖物品自带的加成。
        event.addModifier(attribute, modifier, def.slot().toVanilla());
    }
}
