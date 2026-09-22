package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.api.damagetype.DamageTypeRegistry;
import com.vestudio.dmmod.api.damagetype.DamageTypeSet;

/**
 * 内置伤害类型的注册。
 *
 * <p>把类型与其标签的对应关系集中在一处，避免散落各处。
 *
 * <h2>标签文件位置</h2>
 * <pre>
 *   data/damagemodernization/tags/damage_type/physical.json      （排除式）
 *   data/damagemodernization/tags/damage_type/magic.json         （包含式）
 * </pre>
 *
 * <p>注册是幂等的：重复调用不会产生重复项。
 */
public final class BuiltInDamageTypes {

    private static volatile boolean registered = false;

    private BuiltInDamageTypes() {
    }

    /**
     * 注册内置伤害类型。
     *
     * <p>应在 mod 构造阶段调用，确保任何伤害结算前已就绪。
     */
    public static synchronized void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        // 物理采用排除法：不在 non_physical 名单内的一律算物理。
        // 这样后续新增伤害类型默认归入物理，无需回来补登记。
        DamageTypeRegistry.registerExclusive(DamageTypeSet.PHYSICAL, "non_physical");

        // 魔法采用包含式：只有明确登记的类型才算魔法。
        // 原版的药水/状态效果伤害（magic、indirect_magic）即属于此类。
        DamageTypeRegistry.register(DamageTypeSet.MAGIC, "magic");

        // 火焰：复用原版的 is_fire 判定语义，单独列一份标签，
        // 便于后续按需调整而不受原版影响。
        DamageTypeRegistry.register(DamageTypeSet.FIRE, "fire");
    }
}
