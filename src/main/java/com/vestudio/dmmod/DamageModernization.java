package com.vestudio.dmmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.damage.BuiltInDamageTypes;
import com.vestudio.dmmod.damage.zone.BuiltInZones;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
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

        // 注册内置伤害类型（物理 / 魔法 / 火焰）。
        // 必须在任何伤害结算前完成，否则类型判定会缺少标签映射。
        BuiltInDamageTypes.registerAll();

        modEventBus.addListener(this::commonSetup);

        // 把「基础攻击力」等属性注入到所有生物身上，
        // 这是「所有生物的所有伤害」都能走四乘区的前提。
        modEventBus.addListener(this::onEntityAttributeModification);

        // 注册配置。
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 加载数据文件（属性定义、乘区与公式）。
        // 放在构造阶段，确保任何伤害结算发生前已就绪。
        com.vestudio.dmmod.formula.DataRepository.load(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        // 若装有饰品栏 mod，额外接上它的属性事件。
        // 饰品栏不读取原版属性组件，只有走它自己的事件才会生效。
        // 未安装时本调用只打印一条日志，不加载任何 Curios 类型。
        com.vestudio.dmmod.damage.CuriosCompat.ensureRegistered();
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

            // 伤害类型相关的子项：物理增伤、魔法增伤（攻方）与物理减伤（守方）。
            addIfAbsent(event, type, DMAttributes.PHYSICAL_AMPLIFIER);
            addIfAbsent(event, type, DMAttributes.MAGIC_AMPLIFIER);
            addIfAbsent(event, type, DMAttributes.PHYSICAL_RESISTANCE);
            addIfAbsent(event, type, DMAttributes.CRIT_DAMAGE_TAKEN_REDUCTION);

            // 这三个属性的默认值由配置驱动。
            addOrDefault(event, type, DMAttributes.DAMAGE_MULTIPLIER, damageMultiplier);
            addOrDefault(event, type, DMAttributes.CRIT_CHANCE, critChance);
            addOrDefault(event, type, DMAttributes.CRIT_DAMAGE, critDamage);
            // 暴击伤害的加算子项（与倍率本体区分）。
            addIfAbsent(event, type, DMAttributes.CRIT_DAMAGE_BONUS);

            // 生命值体系：基础生命值默认取原版血量，
            // 使「基础生命值 = max_health」成立，百分比才有正确基准。
            addOrDefault(event, type, DMAttributes.BASE_HEALTH,
                    vanillaAttributeBase(type, Attributes.MAX_HEALTH, 20.0D));
            addIfAbsent(event, type, DMAttributes.HEALTH_PERCENT);
            addIfAbsent(event, type, DMAttributes.HEALTH_FLAT);

            // 护甲体系：与生命值同一套逻辑。
            // 基础护甲/韧性默认取原版对应属性，使「基础值 = 原版值」成立。
            addOrDefault(event, type, DMAttributes.BASE_ARMOR,
                    vanillaAttributeBase(type, Attributes.ARMOR, 0.0D));
            addIfAbsent(event, type, DMAttributes.ARMOR_PERCENT);
            addIfAbsent(event, type, DMAttributes.ARMOR_FLAT);

            addOrDefault(event, type, DMAttributes.BASE_ARMOR_TOUGHNESS,
                    vanillaAttributeBase(type, Attributes.ARMOR_TOUGHNESS, 0.0D));
            addIfAbsent(event, type, DMAttributes.ARMOR_TOUGHNESS_PERCENT);
            addIfAbsent(event, type, DMAttributes.ARMOR_TOUGHNESS_FLAT);
        }

        LOGGER.info(
                "Injected damage attribute defaults (critChance={}, critDamage={}, damageMultiplier={})",
                critChance, critDamage, damageMultiplier);
    }

    /**
     * {@return 该实体类型上某个原版属性的默认值}
     *
     * <p>用于把「基础值属性」的默认值对齐到原版对应属性——
     * 生命值对 {@code max_health}、护甲对 {@code armor}、韧性对 {@code armor_toughness}。
     * 基准不对的话，「基础值 × (1 + 百分比)」会从一开始就算错。
     *
     * @param type      实体类型
     * @param attribute 原版属性
     * @param fallback  取不到时使用的兜底值
     */
    private static double vanillaAttributeBase(EntityType<? extends LivingEntity> type,
                                               net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                               double fallback) {
        try {
            var supplier = net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier(type);
            if (supplier.hasAttribute(attribute)) {
                return supplier.getBaseValue(attribute);
            }
        } catch (Exception e) {
            // 取不到时退回兜底值。
        }
        return fallback;
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
        // 若装有星辉，把它的暴击 perk 并入本 mod 的暴击区。
        //
        // 必须放在这里，而不是 mod 构造阶段：mod 构造是<b>并行</b>的，
        // 我们可能在星辉建好自己的注册表之前就去取，从而误判成「不可用」。
        // 通用初始化发生在全部 mod 构造与注册完成之后，是安全的时机。
        com.vestudio.dmmod.damage.AstralCompat.ensureRegistered();

        LOGGER.info("Damage_Modernization loaded: four-zone damage model active (enabled={})",
                Config.ENABLE_FOUR_ZONE_MODEL.getAsBoolean());
    }
}
