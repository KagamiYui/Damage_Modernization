package com.vestudio.dmmod.damage.zone;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.zone.DamageZoneRegistry;
import com.vestudio.dmmod.api.zone.IDamageZone;

import net.minecraft.resources.ResourceLocation;

/**
 * 内置乘区的注册入口。
 *
 * <p>四个内置乘区并非硬编码在公式里，而是与第三方 mod 的乘区一样
 * 通过 {@link DamageZoneRegistry} 注册。这样做的好处是：
 * <ul>
 *   <li>模组包可以<b>注销</b>某个内置乘区，用自定义实现替换；</li>
 *   <li>内置与第三方乘区走完全相同的执行路径，行为可预期；</li>
 *   <li>注册顺序由优先级统一管理，不存在「内置优先」的隐式规则。</li>
 * </ul>
 *
 * <p>注册是<b>幂等</b>的：重复调用不会产生重复乘区。
 */
public final class BuiltInZones {

    /** 是否已完成注册，避免重复执行。 */
    private static volatile boolean registered = false;

    private BuiltInZones() {
    }

    /**
     * 注册全部内置乘区。
     *
     * <p>应当在 mod 构造阶段调用，确保在任何伤害发生前完成注册。
     */
    public static synchronized void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        DamageZoneRegistry.register(new AttackPowerZone());
        DamageZoneRegistry.register(new DamageAmplifierZone());
        DamageZoneRegistry.register(new DamageMultiplierZone());
        DamageZoneRegistry.register(new CritZone());

        // 这里处于 mod 构造阶段，配置尚未加载，
        // 因此必须使用带兜底值的访问器，否则会抛出
        // "Cannot get config value before config is loaded"。
        if (Config.getBooleanOr(Config.LOG_ZONE_REGISTRATION, true)) {
            for (IDamageZone zone : DamageZoneRegistry.getZones()) {
                DamageModernization.LOGGER.info(
                        "[DM] damage zone registered: {} (priority={})",
                        zone.id(), zone.priority());
            }
        }
    }

    /**
     * 注销全部内置乘区。
     *
     * <p>供模组包或测试代码用自定义实现完全替换内置逻辑。
     */
    public static void unregisterAll() {
        DamageZoneRegistry.unregister(id("attack_power"));
        DamageZoneRegistry.unregister(id("damage_amplifier"));
        DamageZoneRegistry.unregister(id("damage_multiplier"));
        DamageZoneRegistry.unregister(id("critical"));
        registered = false;
    }

    /**
     * 构造本 mod 命名空间下的资源位置。
     *
     * @param path 路径
     * @return 资源位置
     */
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DamageModernization.MODID, path);
    }
}
