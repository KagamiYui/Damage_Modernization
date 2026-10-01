package com.vestudio.dmmod.damage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/**
 * 属性输出的后处理入口。
 *
 * <p>由 {@code AttributeInstanceMixin} 在 {@code AttributeInstance.getValue}
 * 返回之后调用，拿到的是<b>原版算出来的值</b>。这里决定交出去的是什么。
 *
 * <h2>当前阶段</h2>
 * 第一步只做<b>链路验证</b>：原样返回，只记录「拦截到了哪些属性」。
 * 数值必须与改造前完全一致——如果这里返回了别的值，说明接线错了。
 *
 * <h2>性能约束</h2>
 * 这个方法挂在高频的 {@code getValue} 上（实测十几秒六十余万次），
 * 而且<b>客户端与服务端都会跑</b>，因此必须极轻：
 * 现阶段只做一次并发集合查询，且仅在前若干次调用时记录。
 *
 * <p>后续加入真正的变换时同样要保持「几次算术以内」。
 * 任何需要遍历、查表或跨属性读取的计算，都应当改由脏标记驱动的路径去算，
 * 而不是塞进这里。
 */
public final class AttributePostProcess {

    /** 最多记录多少个不同属性的拦截情况，避免刷屏。 */
    private static final int LOG_LIMIT = 16;

    /** 已记录过的属性，用于去重。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** 累计拦截次数，供诊断读取。 */
    private static final AtomicLong CALLS = new AtomicLong();

    private AttributePostProcess() {
    }

    /**
     * 对原版算出的属性值做后处理。
     *
     * @param instance 属性实例（可读到「是哪个属性」）
     * @param vanilla  原版算出的值
     * @return 要交出去的值；当前阶段与原版值相同
     */
    public static double apply(AttributeInstance instance, double vanilla) {
        long calls = CALLS.incrementAndGet();

        // 诊断期：前几次无条件记录，确认注入是否真的被执行。
        if (calls <= 5) {
            DamageModernization.LOGGER.info(
                    "属性输出已接管（第 {} 次）[{}] 原版值 = {}", calls, nameOf(instance), vanilla);
        } else if (LOGGED.size() < LOG_LIMIT) {
            // 之后只在前 LOG_LIMIT 个不同属性上各记录一次，避免刷屏。
            String name = nameOf(instance);
            if (LOGGED.add(name)) {
                DamageModernization.LOGGER.info(
                        "属性输出已接管 [{}] 原版值 = {}（累计拦截 {} 次）", name, vanilla, calls);
            }
        }

        // 第一步：原样交还。数值与改造前必须完全一致。
        return vanilla;
    }

    /**
     * {@return 累计拦截次数}
     */
    public static long callCount() {
        return CALLS.get();
    }

    /**
     * {@return 已记录过的属性个数}
     */
    public static int seenAttributeCount() {
        return LOGGED.size();
    }

    /**
     * {@return 属性的标识名}
     *
     * @param instance 属性实例
     */
    private static String nameOf(AttributeInstance instance) {
        return instance.getAttribute().unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
    }
}
