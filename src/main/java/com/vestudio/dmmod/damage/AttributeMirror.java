package com.vestudio.dmmod.damage;

import java.util.ArrayList;
import java.util.List;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * 属性镜像工具：把一个属性的基础值与修饰符复制到另一个属性上。
 *
 * <p>本 mod 的核心手法是「<b>语义重写</b>」：原版的某个属性（攻击伤害、最大生命值）
 * 语义是「最终值」，我们把它改写成「基础值」，并在旁边维护一份副本，
 * 供乘区体系在其上叠加百分比与固定加成。
 *
 * <p>之所以抽出本类，是因为攻击力与生命值都需要同一套逻辑，
 * 差别仅在于「镜像来源」「镜像目标」与「前缀」。
 *
 * <h2>清理策略：按前缀清除，而不是靠记忆</h2>
 * 早期实现把「上一轮添加了哪些修饰符」记在一个按实体 UUID 索引的静态表里，
 * 下次据此删除。这种做法在换手、切维度、死亡重生、以及客户端/服务端
 * 各自维护一份表的情况下都可能失效，导致旧加的成残留。
 *
 * <p>现在改为：先把目标属性上所有带指定前缀的修饰符一并清除，再重建。
 * 无论上一轮处于什么状态都不会有残留——不依赖任何跨调用的记忆。
 */
public final class AttributeMirror {

    /** 基础攻击力使用的镜像前缀。 */
    public static final String ATTACK_POWER_PREFIX = "dm_attack_power_mirror_";

    /** 基础生命值使用的镜像前缀。 */
    public static final String HEALTH_PREFIX = "dm_health_mirror_";

    private AttributeMirror() {
    }

    /**
     * 把 {@code source} 的基础值与修饰符镜像到 {@code target}。
     *
     * <p>基础值直接搬迁；修饰符以相同数值与运算方式复制。
     * 两步互不重叠，因此不会把同一份加成算两遍。
     *
     * @param target 目标属性实例
     * @param source 来源属性实例
     * @param prefix 本组镜像使用的 id 前缀（用于识别与清理）
     */
    public static void mirror(AttributeInstance target,
                             AttributeInstance source,
                             String prefix) {
        if (target == null || source == null) {
            return;
        }

        syncBaseValue(target, source);
        rebuildModifiers(target, source, prefix);
    }

    /**
     * 搬迁基础值：{@code target.baseValue ← source.baseValue}。
     *
     * @param target 目标属性
     * @param source 来源属性
     */
    public static void syncBaseValue(AttributeInstance target, AttributeInstance source) {
        double sourceBase = source.getBaseValue();
        if (!Double.isFinite(sourceBase) || sourceBase < 0.0D) {
            return;
        }
        // 仅在确有差异时写入，避免每 tick 触发属性脏标记与网络同步。
        if (Math.abs(target.getBaseValue() - sourceBase) > 1.0E-6D) {
            target.setBaseValue(sourceBase);
        }
    }

    /**
     * 重建目标属性上的镜像修饰符。
     *
     * <p>先按前缀清除旧的，再按来源当前状态重建，因此是<b>幂等</b>的。
     *
     * @param target 目标属性
     * @param source 来源属性
     * @param prefix 镜像前缀
     */
    public static void rebuildModifiers(AttributeInstance target,
                                        AttributeInstance source,
                                        String prefix) {
        removeMirrors(target, prefix);

        int index = 0;
        for (AttributeModifier modifier : source.getModifiers()) {
            // 跳过本 mod 自己写入的修饰符。
            // 既避免镜像的自我复制，也避免把「计算结果」当成「原始数据」复制过去——
            // 例如 max_health 上承载最终血量的那个修饰符，
            // 若被镜像进 base_health，就会被当成装备加成而重复计算。
            if (isOurs(modifier)) {
                continue;
            }

            ResourceLocation mirrorId = ResourceLocation.fromNamespaceAndPath(
                    DamageModernization.MODID, prefix + index++);

            AttributeModifier mirror = new AttributeModifier(
                    mirrorId, modifier.amount(), modifier.operation());

            try {
                target.addOrUpdateTransientModifier(mirror);
            } catch (Exception e) {
                // 单个修饰符失败不应中断整体结算，仅记录调试日志。
                DamageModernization.LOGGER.debug(
                        "Failed to mirror modifier {} onto {}", modifier.id(), prefix, e);
            }
        }
    }

    /**
     * 清除目标属性上所有带指定前缀的镜像修饰符。
     *
     * @param target 目标属性
     * @param prefix 镜像前缀
     */
    public static void removeMirrors(AttributeInstance target, String prefix) {
        if (target == null) {
            return;
        }

        // 先收集再删除，避免在遍历过程中修改集合。
        List<ResourceLocation> toRemove = new ArrayList<>();
        for (AttributeModifier modifier : target.getModifiers()) {
            if (isMirror(modifier, prefix)) {
                toRemove.add(modifier.id());
            }
        }
        for (ResourceLocation id : toRemove) {
            target.removeModifier(id);
        }
    }

    /**
     * {@return 该修饰符是否属于指定前缀的镜像}
     *
     * @param modifier 修饰符
     * @param prefix   镜像前缀
     */
    public static boolean isMirror(AttributeModifier modifier, String prefix) {
        return modifier.id().getNamespace().equals(DamageModernization.MODID)
                && modifier.id().getPath().startsWith(prefix);
    }

    /**
     * {@return 该修饰符是否由本 mod 写入}
     *
     * <p>凡是本 mod 命名空间下的修饰符都不应被镜像——
     * 它们要么是镜像产物（复制会自我繁殖），
     * 要么是计算结果（复制会把它当成原始数据重复计算）。
     *
     * @param modifier 修饰符
     */
    public static boolean isOurs(AttributeModifier modifier) {
        return modifier.id().getNamespace().equals(DamageModernization.MODID);
    }
}
