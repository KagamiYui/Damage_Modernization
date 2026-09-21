package com.vestudio.dmmod.api.zone;

import net.minecraft.resources.ResourceLocation;

/**
 * 伤害乘区扩展接口。
 *
 * <p>其他 mod 通过实现本接口并注册到 {@link DamageZoneRegistry}，
 * 即可参与伤害合成，无需依赖本 mod 的内部实现。
 *
 * <h2>设计约定</h2>
 * <ul>
 *   <li><b>幂等</b>：同一次伤害中，一个乘区只应被应用一次。管线保证这一点，
 *       但实现内部若有随机数等副作用，仍需自行注意。</li>
 *   <li><b>不改最终结果</b>：乘区应当通过 {@link DamageContext} 的
 *       {@code addXxx} / {@code multiplyXxx} 方法表达自己的贡献，
 *       而不是直接算出伤害。只有在需要完全接管时才使用
 *       {@link DamageContext#overrideFinalDamage(double)}。</li>
 *   <li><b>无异常</b>：乘区抛出异常会被管线捕获并记录日志，
 *       以免个别 mod 的 bug 导致整个伤害系统瘫痪。但请不要依赖这一点。</li>
 * </ul>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * public final class FireDamageZone implements IDamageZone {
 *     @Override
 *     public ResourceLocation id() {
 *         return ResourceLocation.fromNamespaceAndPath("mymod", "fire_bonus");
 *     }
 *
 *     @Override
 *     public int priority() {
 *         return 100;
 *     }
 *
 *     @Override
 *     public void apply(DamageContext ctx) {
 *         // 对燃烧中的目标额外造成 20% 伤害（放入倍率区）
 *         if (ctx.victim().isOnFire()) {
 *             ctx.multiplyDamageMultiplier(1.2D);
 *         }
 *     }
 * }
 * }</pre>
 */
public interface IDamageZone {

    /**
     * {@return 本乘区的唯一标识，用于去重与日志}
     *
     * <p>应当使用自己的命名空间，例如 {@code mymod:fire_bonus}。
     */
    ResourceLocation id();

    /**
     * {@return 执行优先级，数值越小越先执行}
     *
     * <p>内置乘区的优先级见 {@link ZonePriorities}。
     * 后续乘区能看到前面乘区对 {@link DamageContext} 的修改，
     * 因此顺序会影响结果，请谨慎选择。
     */
    default int priority() {
        return ZonePriorities.DEFAULT;
    }

    /**
     * 对伤害上下文施加本乘区的影响。
     *
     * @param context 本次伤害的上下文，可读可改
     */
    void apply(DamageContext context);
}
