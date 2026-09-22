package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 生命值的乘区计算：把原版「最大生命值」重写为一套可成长的体系。
 *
 * <h2>公式</h2>
 * <pre>
 *   基础生命值   = base_health（原版基础值 + 装备等各项加成）
 *   最终最大生命 = 基础生命值 × (1 + 生命值百分比提升) + 固定生命值
 * </pre>
 *
 * <h2>结果写回 max_health，但绝不改它的基础值</h2>
 * 原版 {@code max_health} 被血条、受伤上限、重生、{@code setHealth} 钳制等
 * 大量逻辑读取，无法逐一改写，因此保留它作为最终结果的载体，
 * 由其它系统照常读取。
 *
 * <p>但这里有个<b>必须避开的反馈回路</b>：结果写回 max_health 之后，
 * 若下一轮又把「含我们自己贡献的 max_health」当成基础生命值再乘一次百分比，
 * 数值会指数膨胀（或反向缩水）。
 *
 * <p>因此：
 * <ol>
 *   <li>结果以<b>修饰符</b>形式叠加，<b>绝不改动 max_health 的基础值</b>，
 *       基础值因此始终保持着原版数值，随时可以安全地当作输入；</li>
 *   <li>镜像时跳过本 mod 自己写入的修饰符，
 *       避免把「计算结果」误当成「装备加成」重复计入基础生命值。</li>
 * </ol>
 * 这样每轮输入都是稳定的原始值，计算幂等。
 *
 * <h2>装备加成进「基础生命值」</h2>
 * 装备、附魔、药水提供的生命加成是 max_health 上的<b>修饰符</b>，
 * 它们会被镜像进 base_health，因此计入「基础生命值」——
 * 也就是显示时位于加号<b>前面</b>的那部分。
 */
public final class HealthCalculator {

    /** 写回 max_health 时使用的修饰符 id 路径。 */
    private static final String MAX_HEALTH_MODIFIER = "dm_computed_max_health";

    /** 原版 max_health 的取值下限，避免算出 0 或负数导致实体立刻死亡。 */
    private static final double MIN_MAX_HEALTH = 1.0D;

    /** 浮点比较容差。 */
    private static final double EPSILON = 1.0E-6D;

    private HealthCalculator() {
    }

    /**
     * 重新计算并写回实体的最大生命值。
     *
     * <p>该方法<b>幂等</b>：重复调用只会得到同样结果，不会逐次膨胀或缩水。
     *
     * @param entity 目标实体
     * @return 计算出的最终最大生命值；属性缺失时返回原版值
     */
    public static double resolveMaxHealth(LivingEntity entity) {
        AttributeInstance baseAttr = entity.getAttribute(DMAttributes.BASE_HEALTH);
        AttributeInstance maxAttr = entity.getAttribute(Attributes.MAX_HEALTH);

        // 缺少任一属性时不做任何改动，直接沿用原版行为。
        if (baseAttr == null || maxAttr == null) {
            return maxAttr == null ? 0.0D : maxAttr.getValue();
        }

        // 1. 取 max_health 的基础值。
        //    由于我们只写修饰符、从不改基础值，这里读到的始终是原版数值，
        //    因此不存在把「自己的计算结果」当成输入的问题。
        double rawMaxBase = maxAttr.getBaseValue();
        if (!Double.isFinite(rawMaxBase)) {
            return maxAttr.getValue();
        }

        // 2. 把 max_health 的基础值与修饰符「完整」镜像到 base_health。
        //    修饰符必须一并镜像，否则装备、药水提供的生命加成
        //    就进不了「基础生命值」，会被错误地算到加号后面。
        if (Math.abs(baseAttr.getBaseValue() - rawMaxBase) > EPSILON) {
            baseAttr.setBaseValue(rawMaxBase);
        }
        AttributeMirror.rebuildModifiers(baseAttr, maxAttr, AttributeMirror.HEALTH_PREFIX);

        // 此时 base_health 的总值 = 原版基础值 + 各项加成（装备等），
        // 这正是「基础生命值」。
        double baseHealth = baseAttr.getValue();

        // 3. 叠加百分比与固定加成。
        double percent = attributeValue(entity, DMAttributes.HEALTH_PERCENT);
        double flat = attributeValue(entity, DMAttributes.HEALTH_FLAT);

        double computed = baseHealth * (1.0D + percent) + flat;
        if (!Double.isFinite(computed)) {
            return maxAttr.getValue();
        }
        computed = Math.max(MIN_MAX_HEALTH, computed);

        // 4. 以修饰符形式写回 max_health（不改基础值）。
        setComputedMaxHealth(maxAttr, computed);

        return maxAttr.getValue();
    }

    /**
     * 把最终值以修饰符形式叠加到 max_health 上。
     *
     * <p>修饰符数值 = 目标值 − 不含该修饰符时的值。
     * 因此先移除旧修饰符再取值，才能算出正确的增量。
     *
     * @param maxAttr  max_health 属性实例
     * @param computed 计算出的最终最大生命值
     */
    private static void setComputedMaxHealth(AttributeInstance maxAttr, double computed) {
        // 先清掉上一轮，确保下面读到的“当前值”不含我们自己的贡献。
        maxAttr.removeModifier(id());

        double currentWithoutUs = maxAttr.getValue();
        double delta = computed - currentWithoutUs;

        // 差异极小时不写入，避免每帧触发脏标记与网络同步。
        if (Math.abs(delta) <= EPSILON) {
            return;
        }

        maxAttr.addOrUpdateTransientModifier(new AttributeModifier(
                id(), delta, AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * 清除某个实体上由本类写入的最大生命值修饰符与镜像。
     *
     * <p>应在实体死亡或卸载时调用，避免残留。
     *
     * @param entity 目标实体
     */
    public static void forget(LivingEntity entity) {
        AttributeInstance maxAttr = entity.getAttribute(Attributes.MAX_HEALTH);
        if (maxAttr != null) {
            maxAttr.removeModifier(id());
        }
        AttributeMirror.removeMirrors(
                entity.getAttribute(DMAttributes.BASE_HEALTH),
                AttributeMirror.HEALTH_PREFIX);
    }

    /**
     * {@return 本类使用的修饰符 id}
     */
    private static ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(
                DamageModernization.MODID, MAX_HEALTH_MODIFIER);
    }

    /**
     * 读取属性值；属性不存在时返回 0。
     *
     * @param entity    实体
     * @param attribute 属性
     * @return 属性值
     */
    private static double attributeValue(LivingEntity entity, Holder<Attribute> attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }
}
