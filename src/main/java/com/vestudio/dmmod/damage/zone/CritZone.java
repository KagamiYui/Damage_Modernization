package com.vestudio.dmmod.damage.zone;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.api.zone.DamageContext;
import com.vestudio.dmmod.api.zone.IDamageZone;
import com.vestudio.dmmod.api.zone.ZonePriorities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 内置乘区：暴击伤害。
 *
 * <p>公式：
 * <pre>
 *   暴击伤害区 = 暴击 ? 暴击伤害倍率 : 1.0
 * </pre>
 *
 * <h2>暴击判定的来源</h2>
 * 暴击是否发生由 {@code DamageEventHandler} 在攻击发起时决定：
 * <ul>
 *   <li><b>玩家</b>：由 {@code crit_chance} 属性掷骰，取代原版「跳跃下劈必定暴击」；</li>
 *   <li><b>生物</b>：同样按 {@code crit_chance} 属性掷骰。</li>
 * </ul>
 *
 * <p>本乘区只负责把「是否暴击」翻译成伤害倍率，
 * 并叠加配置中的全局暴击伤害修正。这样判定与数值解耦，
 * 其他 mod 可以在事件里直接 {@code setCritical(true)} 来强制暴击。
 */
public final class CritZone implements IDamageZone {

    /** 乘区标识。 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("damagemodernization", "critical");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return ZonePriorities.CRITICAL;
    }

    @Override
    public void apply(DamageContext context) {
        if (!Config.ENABLE_CRIT_ZONE.getAsBoolean()) {
            // 关闭暴击乘区时不产生暴击，暴击区恒为 1.0。
            context.setCritical(false);
            return;
        }

        if (!context.isCritical()) {
            return;
        }

        LivingEntity attacker = context.attacker();

        // 以属性中的暴击伤害为准；没有攻击者时保留上下文原值。
        if (attacker != null) {
            context.setCritDamage(attacker.getAttributeValue(DMAttributes.CRIT_DAMAGE));
        }

        // 全局暴击伤害加成：以加法方式叠到倍率上，
        // 使模组包可以整体调整暴击强度。
        double bonus = Config.CRIT_ZONE_DAMAGE_BONUS.get();
        if (bonus != 0.0D) {
            context.setCritDamage(context.critDamage() + bonus);
        }
    }
}
