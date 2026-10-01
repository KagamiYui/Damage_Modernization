package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.Config;
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
 * 把「本 mod 的加成属性」翻译成原版属性上的修饰符。
 *
 * <h2>为什么这样做</h2>
 * 原版的属性计算本身就是
 * <pre>
 *   最终值 = (基础值 + Σ加值) × (1 + Σ基础乘算) × Π(1 + 总乘算)
 * </pre>
 * 也就是说，原版<b>原生支持</b>「给基础值加固定量」和「按基础值加百分比」这两件事。
 * 所以我们要表达「护甲 +10%、再 +5」时，不需要自己维护一套基础值属性、
 * 也不需要把算好的最终值写回——
 * 只要把这两个加成挂成原版的修饰符，剩下的交给原版算即可。
 *
 * <p>这正是星辉（Astral Sorcery）的做法：它从不读原版属性值，
 * 只往上面叠加增量修饰符，因此天然没有反馈循环、也不需要脏标记传播。
 *
 * <h2>接受的代价</h2>
 * 交给原版算，就意味着接受原版的属性上下限（例如护甲 0~30、盔甲韧性 0~20）。
 * 解除上限是另一件事，由专门的 mod 负责，这里不做。
 *
 * <h2>更新时机</h2>
 * 本类由低频的刷新路径调用（见 {@code DamageEventHandler} 的每秒刷新）。
 * 更新是<b>幂等</b>的：先按固定 id 移除上一轮写入的修饰符，再按当前值重建，
 * 因此重复调用不会累加；值没变时也不产生多余写入。
 */
public final class AttributeBonusApplier {

    private static final ResourceLocation ARMOR_PERCENT_ID = id("bonus_armor_percent");
    private static final ResourceLocation ARMOR_FLAT_ID = id("bonus_armor_flat");
    private static final ResourceLocation TOUGHNESS_PERCENT_ID = id("bonus_toughness_percent");
    private static final ResourceLocation TOUGHNESS_FLAT_ID = id("bonus_toughness_flat");
    private static final ResourceLocation HEALTH_PERCENT_ID = id("bonus_health_percent");
    private static final ResourceLocation HEALTH_FLAT_ID = id("bonus_health_flat");
    private static final ResourceLocation HEALTH_SCALE_ID = id("scale_health");
    private static final ResourceLocation ATTACK_PERCENT_ID = id("bonus_attack_percent");
    private static final ResourceLocation ATTACK_FLAT_ID = id("bonus_attack_flat");

    private AttributeBonusApplier() {
    }

    /**
     * 把当前实体的加成属性翻译成原版修饰符。
     *
     * @param entity 目标实体
     */
    public static void apply(LivingEntity entity) {
        // 护甲
        applyBonus(entity, Attributes.ARMOR,
                DMAttributes.ARMOR_PERCENT, DMAttributes.ARMOR_FLAT,
                ARMOR_PERCENT_ID, ARMOR_FLAT_ID);

        // 盔甲韧性
        applyBonus(entity, Attributes.ARMOR_TOUGHNESS,
                DMAttributes.ARMOR_TOUGHNESS_PERCENT, DMAttributes.ARMOR_TOUGHNESS_FLAT,
                TOUGHNESS_PERCENT_ID, TOUGHNESS_FLAT_ID);

        // 生命值
        applyBonus(entity, Attributes.MAX_HEALTH,
                DMAttributes.HEALTH_PERCENT, DMAttributes.HEALTH_FLAT,
                HEALTH_PERCENT_ID, HEALTH_FLAT_ID);

        // 基础生命值的全局缩放：等价的表达就是给 max_health
        // 挂一个「按基础值乘算」的修饰符，缩放 1.1 对应增量 0.1。
        applyScale(entity, Attributes.MAX_HEALTH, Config.HEALTH_SCALE.get(), HEALTH_SCALE_ID);

        // 攻击力：加成同样挂到原版 attack_damage 上，
        // 伤害管线直接读它的总值即可，不再需要自己维护「基础攻击力」。
        applyBonus(entity, Attributes.ATTACK_DAMAGE,
                DMAttributes.ATTACK_POWER_PERCENT, DMAttributes.ATTACK_POWER_FLAT,
                ATTACK_PERCENT_ID, ATTACK_FLAT_ID);
    }

    /**
     * 给一个原版属性挂上「百分比」与「固定值」两种加成。
     *
     * @param entity      目标实体
     * @param target      要加成的原版属性
     * @param percentAttr 百分比来源属性
     * @param flatAttr    固定值来源属性
     * @param percentId   百分比修饰符的 id（固定，用于幂等更新）
     * @param flatId      固定值修饰符的 id
     */
    private static void applyBonus(LivingEntity entity,
                                   Holder<Attribute> target,
                                   Holder<Attribute> percentAttr,
                                   Holder<Attribute> flatAttr,
                                   ResourceLocation percentId,
                                   ResourceLocation flatId) {
        AttributeInstance instance = entity.getAttribute(target);
        if (instance == null) {
            return;
        }

        // 先摘掉自己上一轮写入的，保证幂等。
        instance.removeModifier(percentId);
        instance.removeModifier(flatId);

        double percent = valueOf(entity, percentAttr);
        double flat = valueOf(entity, flatAttr);

        // 百分比走「基础乘算」：原版会把它乘在「基础值 + 所有加值」之上。
        if (percent != 0.0D) {
            instance.addTransientModifier(new AttributeModifier(
                    percentId, percent, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }

        // 固定值走「加值」。
        if (flat != 0.0D) {
            instance.addTransientModifier(new AttributeModifier(
                    flatId, flat, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /**
     * 给原版属性挂一个「按基础值缩放」的修饰符。
     *
     * @param entity 目标实体
     * @param target 目标属性
     * @param scale  缩放倍数（1.0 表示不变）
     * @param id     修饰符 id
     */
    private static void applyScale(LivingEntity entity,
                                   Holder<Attribute> target,
                                   double scale,
                                   ResourceLocation id) {
        AttributeInstance instance = entity.getAttribute(target);
        if (instance == null) {
            return;
        }

        instance.removeModifier(id);

        double delta = scale - 1.0D;
        if (delta != 0.0D) {
            instance.addTransientModifier(new AttributeModifier(
                    id, delta, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /**
     * 读加成来源属性的值。
     *
     * @param entity    目标实体
     * @param attribute 属性
     * @return 属性值；属性不存在时为 0
     */
    private static double valueOf(LivingEntity entity, Holder<Attribute> attribute) {
        return entity.getAttribute(attribute) == null
                ? 0.0D
                : entity.getAttributeValue(attribute);
    }

    /**
     * {@return 本 mod 命名空间下的修饰符 id}
     *
     * @param path 路径
     */
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DamageModernization.MODID, path);
    }
}
