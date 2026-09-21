package com.vestudio.dmmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.damage.zone.BuiltInZones;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

/**
 * Damage_Modernization —— 重构 Minecraft 底层伤害构成。
 *
 * <p>原版伤害是单一的「攻击伤害」数值，直接参与扣血。
 * 本 mod 把它重构为四个互相独立的乘区：
 * <pre>
 *   最终伤害 = 攻击力区 × 伤害提升区 × 伤害倍率区 × 暴击伤害区
 * </pre>
 *
 * <p>其中「攻击力区」= 基础攻击力 × (1 + 攻击力百分比提升) + 固定攻击力，
 * 而「基础攻击力」由原版 {@code attack_damage} 语义重写而来。
 *
 * <p>核心实现位于 {@code com.vestudio.dmmod.damage} 包：
 * <ul>
 *   <li>{@code BaseAttackPowerConverter} —— 攻击伤害 → 基础攻击力的重写</li>
 *   <li>{@code DamageZones} —— 四乘区运算</li>
 *   <li>{@code DamageEventHandler} —— 接管原版伤害管线</li>
 * </ul>
 */
@Mod(DamageModernization.MODID)
public class DamageModernization {

    /** mod id，必须与 neoforge.mods.toml 中的 modId 保持一致。 */
    public static final String MODID = "damagemodernization";

    /** 全局日志器。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * mod 主构造器。
     *
     * @param modEventBus  mod 事件总线
     * @param modContainer 当前 mod 容器，用于注册配置
     */
    public DamageModernization(IEventBus modEventBus, ModContainer modContainer) {
        // 注册自定义属性。必须注册到 mod 事件总线，属性才会进入游戏注册表。
        DMAttributes.ATTRIBUTES.register(modEventBus);

        // 注册四个内置乘区。它们与第三方乘区走完全相同的执行路径，
        // 因此模组包可以注销内置实现并替换为自己的版本。
        BuiltInZones.registerAll();

        modEventBus.addListener(this::commonSetup);

        // 把「基础攻击力」等属性注入到所有生物身上，
        // 这是「所有生物的所有伤害」都能走四乘区的前提。
        modEventBus.addListener(this::onEntityAttributeModification);

        // 注册配置。
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    /**
     * 为所有可拥有属性的生物类型注入本 mod 的属性。
     *
     * <p>这是实现需求中「生效范围：所有生物的所有伤害」的关键一步：
     * 若不注入，非玩家生物将没有 {@code base_attack_power} 等属性，
     * 四乘区运算只能退化为读取原版数值。
     *
     * <h2>为什么在这里读取配置</h2>
     * 属性在<b>注册阶段</b>就会被实例化，而配置加载晚于注册，
     * 因此属性定义处只能使用兜底默认值。
     * 本事件在配置加载<b>之后</b>触发，且正是 NeoForge 用来构建
     * 实体属性默认值映射的地方，所以在这里写配置值是正确时机。
     *
     * <p>注入时使用属性默认值，因此不会改变任何生物的现有强度；
     * 只有被配置显式调整的默认值才会不同。
     *
     * @param event 属性修改事件
     */
    private void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        // 从配置读取「默认数值」，让模组包可以调整生物的基础强度。
        // 用带兜底的访问器：即使配置尚未加载也不会崩溃。
        double critChance = Config.critChanceOrDefault();
        double critDamage = Config.critDamageOrDefault();
        double damageMultiplier = Config.damageMultiplierOrDefault();

        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            // 逐个检查后再添加，避免与已存在的属性冲突。
            addIfAbsent(event, type, DMAttributes.BASE_ATTACK_POWER);
            addIfAbsent(event, type, DMAttributes.ATTACK_POWER_PERCENT);
            addIfAbsent(event, type, DMAttributes.ATTACK_POWER_FLAT);
            addIfAbsent(event, type, DMAttributes.DAMAGE_AMPLIFIER);

            // 这三个属性的默认值由配置驱动。
            addOrDefault(event, type, DMAttributes.DAMAGE_MULTIPLIER, damageMultiplier);
            addOrDefault(event, type, DMAttributes.CRIT_CHANCE, critChance);
            addOrDefault(event, type, DMAttributes.CRIT_DAMAGE, critDamage);
        }

        LOGGER.info(
                "Injected damage attribute defaults (critChance={}, critDamage={}, damageMultiplier={})",
                critChance, critDamage, damageMultiplier);
    }

    /**
     * 在实体类型尚未拥有指定属性时注入（使用属性自身默认值）。
     *
     * @param event     属性修改事件
     * @param type      实体类型
     * @param attribute 待注入的属性
     */
    private static void addIfAbsent(EntityAttributeModificationEvent event,
                                    EntityType<? extends LivingEntity> type,
                                    net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        if (!event.has(type, attribute)) {
            event.add(type, attribute);
        }
    }

    /**
     * 注入属性并显式指定默认值；已存在时不覆盖，避免与整合包的配置冲突。
     *
     * @param event     属性修改事件
     * @param type      实体类型
     * @param attribute 待注入的属性
     * @param value     默认值
     */
    private static void addOrDefault(EntityAttributeModificationEvent event,
                                     EntityType<? extends LivingEntity> type,
                                     net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                     double value) {
        if (event.has(type, attribute)) {
            // 已存在（例如被其他 mod 先行注入），尊重既有数值。
            return;
        }
        event.add(type, attribute, value);
    }

    /**
     * 通用初始化：打印一条启动信息，便于在日志中确认 mod 已加载。
     *
     * @param event 通用初始化事件
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Damage_Modernization loaded: four-zone damage model active (enabled={})",
                Config.ENABLE_FOUR_ZONE_MODEL.getAsBoolean());
    }
}
