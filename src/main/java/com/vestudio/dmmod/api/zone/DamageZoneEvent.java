package com.vestudio.dmmod.api.zone;

import net.neoforged.bus.api.Event;

/**
 * 伤害合成事件：在四乘区运算<b>之前</b>触发。
 *
 * <p>与 {@link DamageZoneRegistry} 注册长期乘区不同，
 * 本事件适合「按次判断」的逻辑——例如根据目标的当前状态、
 * 或与其他 mod 的临时状态交互。
 *
 * <h2>与注册表的分工</h2>
 * <ul>
 *   <li><b>长期规则</b>（装备词条、天赋）→ 注册 {@link IDamageZone}，性能更好；</li>
 *   <li><b>按次判断</b>（状态联动、一次性效果）→ 监听本事件。</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * @SubscribeEvent
 * public static void onDamageZone(DamageZoneEvent event) {
 *     DamageContext ctx = event.getContext();
 *     if (ctx.victim().isInWater()) {
 *         ctx.addDamageAmplifier(0.5D); // 水中目标受伤 +50%
 *     }
 * }
 * }</pre>
 *
 * <h2>异常处理</h2>
 * 本事件的监听器抛出的异常会被管线捕获并记录，不会中断伤害结算。
 * 但仍然建议自行处理异常，以便获得更明确的错误来源。
 */
public class DamageZoneEvent extends Event {

    private final DamageContext context;

    /**
     * 构造事件。
     *
     * @param context 本次伤害的上下文
     */
    public DamageZoneEvent(DamageContext context) {
        this.context = context;
    }

    /**
     * {@return 本次伤害的上下文，可读可改}
     */
    public DamageContext getContext() {
        return context;
    }
}
