package com.vestudio.dmmod.formula;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * 一条物品效果定义：给某个物品（或标签）追加属性修饰符。
 *
 * <p>用途是让「武器装备的默认效果」可配置：模组包无需改代码或覆盖原版物品数据，
 * 只需在数据文件里登记即可。
 *
 * <h2>两个槽位维度</h2>
 * 属性修饰符要生效，必须说明「物品放在哪里才算数」。
 * 这里把它拆成两个<b>互相独立</b>的维度，避免名称歧义：
 * <ul>
 *   <li>{@link #slot()} —— <b>原版槽位</b>（主手、护甲……），
 *       用 {@link SlotGroup} 枚举取值；</li>
 *   <li>{@link #curioSlot()} —— <b>饰品槽</b>（{@code ring}、{@code necklace}……），
 *       用字符串取值。</li>
 * </ul>
 * 之所以分开，是因为饰品槽标识（如 {@code head}、{@code feet}、{@code body}）
 * 与原版槽位名<b>存在重名</b>，若混在一个字段里就无法表达「既是原版头盔槽、
 * 又是饰品 head 槽」。分开之后两者可以任意组合。
 *
 * <h2>默认行为</h2>
 * 只写 {@code slot} 不写 {@code curioSlot} 时：
 * <ul>
 *   <li>{@code slot = any} → 原版任意槽位 <b>+</b> 全部饰品槽（兼容性最好）；</li>
 *   <li>其它 {@code slot} → 只按原版槽位生效，不作用于饰品栏
 *       （写 {@code mainhand} 却仍然在戒指上生效显然是违背直觉的）。</li>
 * </ul>
 *
 * <h2>与药水效果的关系</h2>
 * 药水产生的属性修饰符是加在<b>实体属性</b>上的，公式读的就是实体属性总值，
 * 因此药水对伤害体系本来就生效，无需额外配置。
 *
 * @param item       目标物品标识
 * @param tag        目标物品标签（以 # 开头时使用；与 item 二选一）
 * @param attribute  目标属性
 * @param amount     数值
 * @param operation  运算方式
 * @param slot       生效的原版槽位
 * @param curioSlot  生效的饰品槽；{@code null} 表示不作用于饰品栏，
 *                   {@link #CURIO_ANY} 表示全部饰品槽
 * @param comment    说明文本
 */
public record ItemEffectDefinition(
        ResourceLocation item,
        ResourceLocation tag,
        ResourceLocation attribute,
        double amount,
        String operation,
        SlotGroup slot,
        @Nullable String curioSlot,
        String comment) {

    /** 饰品槽通配符：命中全部饰品槽。 */
    public static final String CURIO_ANY = "*";

    /** 表示「明确不作用于饰品栏」的写法。 */
    public static final String CURIO_NONE = "none";

    /** Curios 的槽位命名空间前缀。 */
    private static final String CURIO_PREFIX = "curios:";

    /**
     * 归一化字段。
     *
     * <p>把「未填写」与「填写了但语义等价」的情况收敛成统一表示，
     * 这样下游只要判断 {@code curioSlot != null} 就能知道是否作用于饰品栏。
     *
     * @param slot      原版槽位
     * @param curioSlot 饰品槽原始写法，可为 {@code null}
     * @param item      目标物品标识
     * @param tag       目标物品标签
     * @param attribute 目标属性
     * @param amount    数值
     * @param operation 运算方式
     * @param comment   说明文本
     */
    public ItemEffectDefinition {
        if (slot == null) {
            slot = SlotGroup.ANY;
        }

        if (curioSlot == null) {
            // 未填写：由原版槽位推导默认值。
            curioSlot = slot == SlotGroup.ANY ? CURIO_ANY : null;
        } else {
            String normalized = normalizeCurioSlot(curioSlot);
            if (normalized.isEmpty() || CURIO_NONE.equals(normalized)) {
                curioSlot = null;
            } else if ("any".equals(normalized)) {
                curioSlot = CURIO_ANY;
            } else {
                curioSlot = normalized;
            }
        }
    }

    /**
     * 归一化饰品槽写法：去空白、转小写、去掉 {@code curios:} 前缀。
     *
     * @param raw 原始写法，可为 {@code null}
     * @return 归一化结果；{@code null} 视作空串
     */
    public static String normalizeCurioSlot(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith(CURIO_PREFIX)) {
            value = value.substring(CURIO_PREFIX.length());
        }
        return value;
    }

    /** 生效的原版槽位。 */
    public enum SlotGroup {
        /** 原版任意槽位 —— 默认，兼容性最好。 */
        ANY("any"),
        /** 不作用于原版槽位，只作用于饰品栏。 */
        NONE("none"),
        /** 仅主手。 */
        MAINHAND("mainhand"),
        /** 仅副手。 */
        OFFHAND("offhand"),
        /** 任意手。 */
        HAND("hand"),
        /** 仅护甲槽。 */
        ARMOR("armor"),
        /** 仅头盔。 */
        HEAD("head"),
        /** 仅胸甲。 */
        CHEST("chest"),
        /** 仅护腿。 */
        LEGS("legs"),
        /** 仅靴子。 */
        FEET("feet");

        private final String id;

        SlotGroup(String id) {
            this.id = id;
        }

        /** {@return 数据文件中的标识} */
        public String id() {
            return id;
        }

        /**
         * 按标识解析槽位。
         *
         * @param raw 文本
         * @return 槽位；无法识别时返回 {@code null}
         */
        @Nullable
        public static SlotGroup byId(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            for (SlotGroup s : values()) {
                if (s.id.equalsIgnoreCase(trimmed)) {
                    return s;
                }
            }
            return null;
        }

        /**
         * 解析槽位，无法识别时退回 {@link #ANY}。
         *
         * <p>数据文件的解析路径应当使用 {@link #byId(String)} 并显式报错，
         * 以免把写错的槽位名静默地放大成「任意槽位」。
         *
         * @param raw 文本
         * @return 槽位
         */
        public static SlotGroup byIdOrAny(@Nullable String raw) {
            SlotGroup parsed = byId(raw);
            return parsed == null ? ANY : parsed;
        }

        /**
         * {@return 对应的原版槽位组；{@link #NONE} 返回 {@code null}}
         */
        @Nullable
        public EquipmentSlotGroup toVanilla() {
            return switch (this) {
                case NONE -> null;
                case ANY -> EquipmentSlotGroup.ANY;
                case MAINHAND -> EquipmentSlotGroup.MAINHAND;
                case OFFHAND -> EquipmentSlotGroup.OFFHAND;
                case HAND -> EquipmentSlotGroup.HAND;
                case ARMOR -> EquipmentSlotGroup.ARMOR;
                case HEAD -> EquipmentSlotGroup.HEAD;
                case CHEST -> EquipmentSlotGroup.CHEST;
                case LEGS -> EquipmentSlotGroup.LEGS;
                case FEET -> EquipmentSlotGroup.FEET;
            };
        }
    }

    /**
     * {@return 运算方式}
     *
     * @throws IllegalArgumentException 名称无法识别时抛出
     */
    public AttributeModifier.Operation operationValue() {
        return switch (operation == null ? "add_value" : operation.toLowerCase(Locale.ROOT)) {
            case "add_value", "add" -> AttributeModifier.Operation.ADD_VALUE;
            case "add_multiplied_base", "multiply_base" ->
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total", "multiply_total" ->
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> throw new IllegalArgumentException("未知运算方式: " + operation);
        };
    }

    /**
     * {@return 匹配目标是否使用标签}
     */
    public boolean usesTag() {
        return tag != null;
    }

    /**
     * {@return 是否作用于原版槽位}
     */
    public boolean appliesToVanilla() {
        return slot != SlotGroup.NONE;
    }

    /**
     * {@return 是否作用于饰品栏}
     */
    public boolean appliesToCurio() {
        return curioSlot != null;
    }

    /**
     * 判断本条效果是否命中给定的饰品槽。
     *
     * @param actualSlotId 物品当前所在的饰品槽标识（如 {@code ring}）
     * @return 是否命中
     */
    public boolean curioSlotMatches(@Nullable String actualSlotId) {
        if (curioSlot == null) {
            return false;
        }
        if (CURIO_ANY.equals(curioSlot)) {
            return true;
        }
        return curioSlot.equals(normalizeCurioSlot(actualSlotId));
    }

    /**
     * 校验定义是否自洽。
     *
     * @return 错误说明列表；为空表示通过
     */
    public List<String> validate() {
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();

        if (item == null && tag == null) {
            errors.add("必须提供 item 或 tag 之一");
        }
        if (item != null && tag != null) {
            errors.add("item 与 tag 不能同时提供");
        }
        if (attribute == null) {
            errors.add("必须提供 attribute");
        }
        if (!Double.isFinite(amount)) {
            errors.add("amount 必须是有限数值");
        }
        if (curioSlot != null && !CURIO_ANY.equals(curioSlot) && !isValidCurioSlot(curioSlot)) {
            errors.add("curioSlot 含非法字符: " + curioSlot);
        }
        if (!appliesToVanilla() && !appliesToCurio()) {
            errors.add("slot=none 且 curioSlot=none，效果永远不会生效");
        }
        try {
            operationValue();
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }

        return errors;
    }

    /**
     * 判断饰品槽标识是否合法。
     *
     * <p>饰品槽标识由数据包定义，取值是开放的，因此这里只做字符合法性检查：
     * 允许小写字母、数字、下划线与连字符。
     *
     * @param value 归一化后的标识
     * @return 是否合法
     */
    private static boolean isValidCurioSlot(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
