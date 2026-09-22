package com.vestudio.dmmod.damage;

import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.formula.ItemEffectDefinition;
import com.vestudio.dmmod.formula.ItemEffectRepository;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.event.CurioAttributeModifierEvent;

/**
 * 饰品栏（Curios）适配：把物品效果同样应用到饰品属性上。
 *
 * <h2>为什么需要单独适配</h2>
 * 实测确认（反编译 Curios 的实现）：Curios 并<b>不读取</b>原版的
 * {@code ATTRIBUTE_MODIFIERS} 组件，而是使用它自己的属性组件：
 * <pre>
 *   stack.getOrDefault(CuriosRegistry.CURIO_ATTRIBUTE_MODIFIERS, CurioAttributeModifiers.EMPTY)
 * </pre>
 * 因此仅靠原版的 {@code ItemAttributeModifierEvent} 对饰品<b>无效</b>，
 * 必须额外监听 Curios 自己的 {@link CurioAttributeModifierEvent}。
 *
 * <h2>为什么把互操作代码关在内部类里</h2>
 * Curios 是<b>可选</b>依赖。只要类加载器去解析 {@link CurioAttributeModifierEvent}
 * 这个类型，没装 Curios 的整合包就会抛 {@code NoClassDefFoundError}。
 * 因此这里分两层：
 * <ul>
 *   <li>外层 {@link CuriosCompat} 只用 {@code ModList} 做字符串判断，不引用任何 Curios 类型；</li>
 *   <li>内层 {@link Hook} 才真正引用 Curios 类型，且<b>只在确认装了 Curios 之后</b>
 *       才会被 JVM 加载。</li>
 * </ul>
 * 编译期用 {@code compileOnly} 依赖 Curios，因此这里可以写正常的类型化代码，
 * 而不是到处反射。
 *
 * <h2>槽位语义</h2>
 * 物品效果用 {@link ItemEffectDefinition#curioSlot()} 描述饰品槽，
 * 取值是 Curios 的槽位标识（如 {@code ring}）。
 */
public final class CuriosCompat {

    /** 饰品栏 mod 的 id。 */
    private static final String CURIOS_MODID = "curios";

    private static boolean probed;
    private static boolean available;

    private CuriosCompat() {
    }

    /**
     * 探测 Curios 并注册监听（幂等）。
     *
     * <p>由 mod 初始化流程调用。没装 Curios 时只打印一条信息，不做任何事。
     */
    public static synchronized void ensureRegistered() {
        if (probed) {
            return;
        }
        probed = true;

        // 用 ModList 判断，不触发任何 Curios 类的加载。
        if (!ModList.get().isLoaded(CURIOS_MODID)) {
            DamageModernization.LOGGER.info(
                    "未检测到 Curios，物品效果只对原版装备槽位生效");
            return;
        }

        try {
            Hook.register();
            available = true;
            DamageModernization.LOGGER.info(
                    "检测到 Curios，已启用饰品栏适配（物品效果也会应用到饰品槽）");
        } catch (Throwable t) {
            available = false;
            DamageModernization.LOGGER.error(
                    "Curios 适配注册失败，饰品栏效果将不可用", t);
        }
    }

    /** {@return 饰品栏适配是否已启用} */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * 真正引用 Curios 类型的部分。
     *
     * <p>这个类<b>只能</b>在 {@link #ensureRegistered()} 确认 Curios 存在之后再被触碰，
     * 否则会引发类加载错误。
     */
    private static final class Hook {

        private Hook() {
        }

        /**
         * 注册监听。
         *
         * <p>用方法引用注册，事件总线的泛型可从参数类型推断出
         * {@link CurioAttributeModifierEvent}，不需要手写 Class 参数。
         */
        static void register() {
            NeoForge.EVENT_BUS.addListener(Hook::onCurioAttributeModifier);
        }

        /**
         * 把物品效果追加到饰品属性上。
         *
         * @param event Curios 属性事件
         */
        static void onCurioAttributeModifier(CurioAttributeModifierEvent event) {
            if (ItemEffectRepository.isEmpty()) {
                return;
            }

            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty()) {
                return;
            }

            List<ItemEffectDefinition> effects = ItemEffectRepository.forStack(stack);
            if (effects.isEmpty()) {
                return;
            }

            String slotId = curioSlotId(event);

            int index = 0;
            for (ItemEffectDefinition def : effects) {
                int current = index++;
                try {
                    apply(event, def, current, slotId);
                } catch (Exception e) {
                    // 单条效果出错不应影响其他效果或饰品本身的属性。
                    DamageModernization.LOGGER.error(
                            "饰品属性应用失败: slot={} attribute={}",
                            slotId, def.attribute(), e);
                }
            }
        }

        /**
         * 应用单条效果。
         *
         * @param event  属性事件
         * @param def    效果定义
         * @param index  该物品内的序号，用于生成唯一 id
         * @param slotId 当前饰品槽标识
         */
        private static void apply(CurioAttributeModifierEvent event,
                                  ItemEffectDefinition def,
                                  int index,
                                  String slotId) {
            if (!def.appliesToCurio() || !def.curioSlotMatches(slotId)) {
                return;
            }

            var attributeHolder = BuiltInRegistries.ATTRIBUTE.getHolder(def.attribute());
            if (attributeHolder.isEmpty()) {
                DamageModernization.LOGGER.error(
                        "饰品效果引用了不存在的属性，已跳过: {}", def.attribute());
                return;
            }

            AttributeModifier modifier = new AttributeModifier(
                    ItemEffectRepository.modifierId(def, index),
                    def.amount(),
                    def.operationValue());

            // addModifier 是追加语义，不会覆盖饰品自带的加成。
            event.addModifier(attributeHolder.get(), modifier);
        }

        /**
         * {@return 事件对应的饰品槽标识；取不到时退回通配符}
         *
         * @param event 属性事件
         */
        private static String curioSlotId(CurioAttributeModifierEvent event) {
            try {
                return event.getSlotContext().identifier();
            } catch (Throwable t) {
                return ItemEffectDefinition.CURIO_ANY;
            }
        }
    }
}
