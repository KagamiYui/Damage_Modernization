package com.vestudio.dmmod.damage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 负责把原版的「攻击伤害（attack_damage）」重写为「基础攻击力（base_attack_power）」。
 *
 * <h2>为什么需要「重写」而不是「改键名」</h2>
 * 原版 {@code generic.attack_damage} 的语义是<b>最终伤害</b>：读到这个值之后，
 * 加上蓄力与暴击就直接扣血。若只是把它的显示名改成「基础攻击力」，
 * 「百分比提升攻击力」依旧会作用在最终伤害上，乘区模型就名不副实。
 *
 * <p>本类把 attack_damage 的数值语义整体迁移到 base_attack_power：
 * <ol>
 *   <li><b>基础值搬迁</b>：把 attack_damage 的 baseValue 搬到 base_attack_power 的 baseValue；</li>
 *   <li><b>修饰符镜像</b>：把 attack_damage 上的修饰符（武器加成、药水、装备等）
 *       以同样的数值与运算方式镜像到 base_attack_power。</li>
 * </ol>
 *
 * <p>两步合起来等价于：{@code base_attack_power 的最终值 == attack_damage 的最终值}，
 * 因此「空手 1 + 石剑 3 = 4」这类原版数值关系被完整保留，
 * 同时武器加值、装备加成、药水效果都会被正确继承。
 *
 * <p><b>关键点</b>：搬运的是 baseValue、镜像的是 modifier，
 * 二者互不重叠，因此不会把武器加成算两遍。这也是本类与
 * 「直接复制最终值」做法的本质区别。
 *
 * <h2>兼容性</h2>
 * 原版 attack_damage 及其修饰符<b>不会被移除</b>，以免破坏其他仍在读取它的系统
 * （生物 AI、第三方模组的兼容逻辑等）。本类只是在其旁边维护一份
 * 「语义为标准攻击力」的副本供四乘区使用。
 */
public final class BaseAttackPowerConverter {

    /** 镜像修饰符的 id 前缀，用于识别并清理本类生成的修饰符。 */
    private static final String MIRROR_PREFIX = "dm_base_mirror_";

    /**
     * 记录实体上一次镜像产生的修饰符 id，便于下一轮清理，
     * 避免武器切换或药水到期后残留旧值导致数值虚高。
     *
     * <p>key 为实体 UUID。使用并发 map，因为不同维度实体可能在不同线程结算。
     */
    private static final Map<UUID, List<ResourceLocation>> MIRRORED =
            new ConcurrentHashMap<>();

    private BaseAttackPowerConverter() {
    }

    /**
     * 把实体的原版攻击伤害完整转换为基础攻击力。
     *
     * <p>该方法<b>幂等</b>：重复调用只会得到同样结果，不会累加。
     * 内部先同步基础值，再重建镜像修饰符。
     *
     * @param entity 目标实体
     * @return 转换后的基础攻击力最终值；属性缺失时回退到原版攻击伤害
     */
    public static double resolveBaseAttackPower(LivingEntity entity) {
        AttributeInstance baseAttr = entity.getAttribute(DMAttributes.BASE_ATTACK_POWER);
        AttributeInstance vanillaAttr = entity.getAttribute(Attributes.ATTACK_DAMAGE);

        // 实体没有攻击伤害属性（如盔甲架）时，按空手基准 1.0 处理，
        // 这样它仍能安全地走四乘区管线而不会产生 NaN。
        if (vanillaAttr == null) {
            return baseAttr != null ? sanitize(baseAttr.getValue(), 1.0D) : 1.0D;
        }

        // 实体没有我们注入的基础攻击力属性时，直接回退到原版数值，
        // 保证第三方实体不会因为缺少属性而行为异常。
        if (baseAttr == null) {
            return sanitize(vanillaAttr.getValue(), 1.0D);
        }

        syncBaseValue(baseAttr, vanillaAttr);
        mirrorModifiers(entity, baseAttr, vanillaAttr);

        return sanitize(baseAttr.getValue(), sanitize(vanillaAttr.getValue(), 1.0D));
    }

    /**
     * 搬迁基础值：base_attack_power.baseValue ← attack_damage.baseValue。
     *
     * <p>基础值承载的是「武器/生物自身的固有攻击力」，
     * 例如玩家基础值 1.0、僵尸基础值 3.0。
     */
    private static void syncBaseValue(AttributeInstance baseAttr, AttributeInstance vanillaAttr) {
        double vanillaBase = vanillaAttr.getBaseValue();
        if (!Double.isFinite(vanillaBase) || vanillaBase < 0.0D) {
            return;
        }
        // 仅在确有差异时写入，避免每 tick 触发属性脏标记与网络同步。
        if (Math.abs(baseAttr.getBaseValue() - vanillaBase) > 1.0E-6D) {
            baseAttr.setBaseValue(vanillaBase);
        }
    }

    /**
     * 镜像修饰符：把 attack_damage 上的修饰符复制到 base_attack_power。
     *
     * <p>每轮先清除上一轮镜像的修饰符再重建，确保武器切换、药水到期等
     * 情况下的数值始终与原版攻击伤害保持一致。
     *
     * <p>这些修饰符以 transient 方式添加，不写入存档，
     * 因为它们每轮都会从原版属性重新推导，持久化反而会造成陈旧数据。
     */
    private static void mirrorModifiers(LivingEntity entity,
                                        AttributeInstance baseAttr,
                                        AttributeInstance vanillaAttr) {
        List<ResourceLocation> previous = MIRRORED.get(entity.getUUID());

        // 清理上一轮镜像，防止叠加。
        if (previous != null) {
            for (ResourceLocation id : previous) {
                baseAttr.removeModifier(id);
            }
        }

        List<ResourceLocation> applied = new ArrayList<>();
        int index = 0;

        for (AttributeModifier modifier : vanillaAttr.getModifiers()) {
            // 跳过本类自己的镜像，避免自我复制造成指数增长。
            if (modifier.id().getNamespace().equals(DamageModernization.MODID)
                    && modifier.id().getPath().startsWith(MIRROR_PREFIX)) {
                continue;
            }

            ResourceLocation mirrorId = ResourceLocation.fromNamespaceAndPath(
                    DamageModernization.MODID, MIRROR_PREFIX + index++);

            AttributeModifier mirror = new AttributeModifier(
                    mirrorId, modifier.amount(), modifier.operation());

            try {
                baseAttr.addOrUpdateTransientModifier(mirror);
                applied.add(mirrorId);
            } catch (Exception e) {
                // 单个修饰符失败不应中断整体结算，仅记录调试日志。
                DamageModernization.LOGGER.debug(
                        "Failed to mirror modifier {} onto base_attack_power",
                        modifier.id(), e);
            }
        }

        MIRRORED.put(entity.getUUID(), applied);
    }

    /**
     * 读取原版攻击伤害的最终值。
     *
     * <p>用于在没有自定义属性时回退，以及在日志中对照。
     *
     * @param entity 目标实体
     * @return 原版攻击伤害；缺失或非法时返回 1.0
     */
    public static double vanillaAttackDamage(LivingEntity entity) {
        AttributeInstance vanilla = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (vanilla == null) {
            return 1.0D;
        }
        return sanitize(vanilla.getValue(), 1.0D);
    }

    /**
     * 清理某个实体的镜像记录，避免 map 无限增长。
     *
     * <p>应在实体卸载或死亡时调用。
     *
     * @param entity 目标实体
     */
    public static void forget(LivingEntity entity) {
        MIRRORED.remove(entity.getUUID());
    }

    /**
     * {@return 合法化后的数值，非法或负数时返回 fallback}
     */
    private static double sanitize(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0D ? value : fallback;
    }
}
