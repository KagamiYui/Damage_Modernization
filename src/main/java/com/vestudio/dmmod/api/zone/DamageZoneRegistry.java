package com.vestudio.dmmod.api.zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.resources.ResourceLocation;

/**
 * 伤害乘区注册表：其他 mod 通过它把自己的乘区挂进伤害管线。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 在 mod 构造器或 FMLCommonSetupEvent 中注册
 * DamageZoneRegistry.register(new FireDamageZone());
 * }</pre>
 *
 * <h2>线程安全</h2>
 * 注册操作使用并发 map，可在 mod 加载阶段安全调用。
 * 读取路径会缓存排序后的列表，仅在注册表变化时重建，避免每次伤害都排序。
 *
 * <h2>为什么用自建注册表而非事件</h2>
 * 事件适合「每次伤害都要问一遍」的场景，但乘区是<b>长期存在</b>的规则，
 * 每次伤害都遍历事件监听器开销更大。因此这里采用注册表 + 缓存的方式；
 * 若确实需要按次动态决定，可注册一个乘区，在其 {@code apply} 内部判断条件。
 */
public final class DamageZoneRegistry {

    /** 已注册的乘区，以 id 去重。 */
    private static final Map<ResourceLocation, IDamageZone> ZONES = new ConcurrentHashMap<>();

    /** 排序后的缓存，volatile 保证读取端可见性。 */
    private static volatile List<IDamageZone> sortedCache = List.of();

    private DamageZoneRegistry() {
    }

    /**
     * 注册一个伤害乘区。
     *
     * <p>若已存在相同 id 的乘区，会被<b>替换</b>并记录警告，
     * 避免同一 mod 重复注册导致加成翻倍。
     *
     * @param zone 待注册的乘区
     */
    public static void register(IDamageZone zone) {
        if (zone == null || zone.id() == null) {
            DamageModernization.LOGGER.warn("Attempted to register a null damage zone or zone with null id");
            return;
        }

        IDamageZone previous = ZONES.put(zone.id(), zone);
        if (previous != null) {
            DamageModernization.LOGGER.warn(
                    "Damage zone {} was registered more than once; the previous instance has been replaced",
                    zone.id());
        }

        rebuildCache();
    }

    /**
     * 注销一个伤害乘区。
     *
     * @param id 乘区标识
     * @return 是否确有乘区被移除
     */
    public static boolean unregister(ResourceLocation id) {
        boolean removed = ZONES.remove(id) != null;
        if (removed) {
            rebuildCache();
        }
        return removed;
    }

    /**
     * {@return 当前所有乘区，已按优先级排序}
     *
     * <p>返回的是不可变列表，可安全遍历。
     */
    public static List<IDamageZone> getZones() {
        return sortedCache;
    }

    /**
     * {@return 指定 id 的乘区，不存在时返回 null}
     *
     * @param id 乘区标识
     */
    public static IDamageZone getZone(ResourceLocation id) {
        return ZONES.get(id);
    }

    /** {@return 已注册的乘区数量} */
    public static int size() {
        return ZONES.size();
    }

    /** 清空所有乘区（主要供测试使用）。 */
    public static void clear() {
        ZONES.clear();
        rebuildCache();
    }

    /**
     * 重建排序缓存。
     *
     * <p>排序规则：优先级升序；优先级相同时按 id 字典序，
     * 保证结果<b>确定</b>——否则相同优先级下不同 JVM 可能产生不同伤害。
     */
    private static void rebuildCache() {
        List<IDamageZone> list = new ArrayList<>(ZONES.values());
        list.sort((a, b) -> {
            int byPriority = Integer.compare(a.priority(), b.priority());
            if (byPriority != 0) {
                return byPriority;
            }
            return a.id().toString().compareTo(b.id().toString());
        });
        sortedCache = Collections.unmodifiableList(list);
    }
}
