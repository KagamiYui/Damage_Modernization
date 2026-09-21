package com.vestudio.dmmod.damage;

/**
 * 攻击上下文：在一次攻击结算期间，于 {@code Player.attack()} 与后续的
 * {@code LivingIncomingDamageEvent} 之间传递「本次攻击的原始基础攻击力」与暴击状态。
 *
 * <h2>为什么必须传递上下文</h2>
 * 原版 {@code Player.attack()} 在处理完伤害后会立刻调用 {@code target.hurt()}，
 * 但在调用之前，它已经把「蓄力冷却」「附魔加成」「暴击倍率」全部乘进了数值，
 * 原始的基础攻击力被彻底抹去、无法还原。
 *
 * <p>与此同时，工具的耐久消耗、附魔触发等逻辑都发生在乘算之后。
 * 如果我们直接重新计算并覆盖伤害，就等于把这部分原版行为绕开。
 *
 * <p>因此本类采用「<b>源头记录 + 目标端消费</b>」的方式：
 * <ol>
 *   <li>在 {@code Player.attack()} 早期触发的 {@code CriticalHitEvent} 中，
 *       记录尚未被乘算的原始基础攻击力与暴击判定；</li>
 *   <li>在 {@code LivingIncomingDamageEvent} 中消费该记录，
 *       用四乘区模型重新合成伤害。</li>
 * </ol>
 *
 * <p>两个事件在同一线程上顺序触发，因此使用 {@link ThreadLocal} 即可安全传递，
 * 无需加锁。栈式结构用以支持嵌套情况（例如连锁攻击、反击效果）。
 */
public final class AttackContext {

    /**
     * 线程本地的上下文栈。
     *
     * <p>使用栈而非单值，是为了正确应对「一次攻击触发另一次攻击」的嵌套场景：
     * 内层攻击结束后会弹栈，外层上下文依然完好。
     */
    private static final ThreadLocal<java.util.ArrayDeque<AttackContext>> STACK =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private final double originalAttackPower;
    private final boolean critical;
    private final long targetId;

    private AttackContext(double originalAttackPower, boolean critical, long targetId) {
        this.originalAttackPower = originalAttackPower;
        this.critical = critical;
        this.targetId = targetId;
    }

    /**
     * 记录一次攻击的原始信息。
     *
     * <p>应在 {@code CriticalHitEvent} 中调用。
     *
     * @param originalAttackPower 尚未经过蓄力与暴击乘算的原始基础攻击力
     * @param critical            本次攻击是否判定为暴击
     * @param targetId            攻击目标的实体 id，用于避免上下文错配
     */
    public static void push(double originalAttackPower, boolean critical, long targetId) {
        STACK.get().push(new AttackContext(originalAttackPower, critical, targetId));
    }

    /**
     * 取出与指定目标匹配的上下文并弹栈。
     *
     * <p>只有目标 id 匹配时才返回，避免并发的无关伤害事件错误地消费掉上下文。
     * 若栈顶不匹配，会沿栈查找；找不到则返回 {@code null}，由调用方回退到原版行为。
     *
     * @param targetId 当前受伤实体的 id
     * @return 匹配的上下文；不存在则为 {@code null}
     */
    public static AttackContext pop(long targetId) {
        java.util.ArrayDeque<AttackContext> stack = STACK.get();
        if (stack.isEmpty()) {
            return null;
        }
        // 栈顶匹配是最常见的情况，直接快速路径返回。
        AttackContext top = stack.peek();
        if (top != null && top.targetId == targetId) {
            stack.pop();
            return top;
        }
        return null;
    }

    /**
     * 清空当前线程的上下文栈。
     *
     * <p>若某次攻击因故没有产生对应的伤害事件（例如被事件取消），
     * 残留的上下文会污染后续结算。因此在攻击结算的收尾阶段应调用本方法兜底。
     */
    public static void clear() {
        STACK.get().clear();
    }

    /** {@return 尚未乘算的原始基础攻击力} */
    public double originalAttackPower() {
        return originalAttackPower;
    }

    /** {@return 本次攻击是否暴击} */
    public boolean critical() {
        return critical;
    }

    /** {@return 被攻击目标的实体 id} */
    public long targetId() {
        return targetId;
    }
}
