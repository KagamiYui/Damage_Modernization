package com.vestudio.dmmod.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.damage.AstralCompat;
import com.vestudio.dmmod.damage.AttributeMirror;
import com.vestudio.dmmod.util.StatFormat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 属性面板：把本 mod 的全部属性以「基础值 + 提升值」的形式展示出来。
 *
 * <h2>显示格式</h2>
 * <pre>
 *   属性名称   总值（基础值+提升值）
 * </pre>
 * 例如：
 * <pre>
 *   基础攻击力          10（4+6）
 *   暴击率              5%（5%+0%）
 * </pre>
 *
 * <h2>为什么用「总值（基础+提升）」而不是只显示总值</h2>
 * 四乘区模型下，玩家最需要判断的是「这个加成到底加在哪一层」。
 * 拆成基础值与提升值后，一眼就能看出某个加成是来自武器基础值，
 * 还是来自装备/药水提供的修饰符。
 *
 * <h2>数值格式化</h2>
 * 数值统一通过属性自身的 {@code toValueComponent} 渲染，
 * 因此百分比类属性（暴击率、暴击伤害等）会自动显示成百分比，
 * 与 tooltip 中的显示完全一致，不会出现两处对不上的情况。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class StatsPanelOverlay {

    /** 面板在屏幕上的定位锚点（注册到 HUD 图层之上）。 */
    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DamageModernization.MODID, "stats_panel");

    // ---- 配色 ----
    private static final int COLOR_BACKGROUND = 0xC8101014;
    private static final int COLOR_BORDER = 0xFF5A5A66;
    private static final int COLOR_TITLE = 0xFFFFD479;
    private static final int COLOR_NAME = 0xFFE0E0E0;
    private static final int COLOR_VALUE = 0xFF8BE28B;
    private static final int COLOR_SEPARATOR = 0xFF3A3A44;

    /** 面板内边距。 */
    private static final int PADDING = 6;
    /** 行高。 */
    private static final int LINE_HEIGHT = 11;
    /** 名称列与数值列之间的最小间距。 */
    private static final int COLUMN_GAP = 12;

    private StatsPanelOverlay() {
    }

    /**
     * 注册 HUD 图层。
     *
     * <p>注册在 {@link VanillaGuiLayers#HOTBAR} 之上，
     * 确保面板覆盖在物品栏等原版 HUD 之上而不被遮挡。
     *
     * @param event 图层注册事件
     */
    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID,
                (guiGraphics, deltaTracker) -> render(guiGraphics));
    }

    /**
     * 渲染面板。
     *
     * @param graphics 绘制上下文
     */
    private static void render(GuiGraphics graphics) {
        if (!StatsPanelKeybind.isVisible()) {
            // 面板关闭时释放缓存，下次打开重新取值。
            clearCache();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // 不在游戏内（例如主菜单、加载中）或玩家尚未就绪时不绘制。
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            clearCache();
            return;
        }

        CachedPanel panel = getPanel(player);
        if (panel == null) {
            return;
        }

        Font font = minecraft.font;
        List<Row> rows = panel.rows();
        Component title = Component.translatable("gui." + DamageModernization.MODID + ".stats_panel.title");

        // ---- 面板尺寸（沿用缓存中量好的宽度，避免每帧重新测量）----
        int titleWidth = font.width(title);
        int contentWidth = Math.max(panel.nameWidth() + COLUMN_GAP + panel.valueWidth(), titleWidth);

        int panelWidth = contentWidth + PADDING * 2;
        int panelHeight = PADDING * 2 + LINE_HEIGHT * 2 + rows.size() * LINE_HEIGHT;

        // ---- 定位：屏幕右侧偏上 ----
        int x = graphics.guiWidth() - panelWidth - 6;
        int y = 6;

        // 背景与边框
        graphics.fill(x, y, x + panelWidth, y + panelHeight, COLOR_BACKGROUND);
        graphics.fill(x, y, x + panelWidth, y + 1, COLOR_BORDER);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + panelHeight, COLOR_BORDER);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, COLOR_BORDER);

        int cursorY = y + PADDING;

        // 标题
        graphics.drawString(font, title, x + PADDING, cursorY, COLOR_TITLE, true);
        cursorY += LINE_HEIGHT;

        // 标题下的分隔线
        graphics.fill(x + PADDING, cursorY - 2, x + panelWidth - PADDING, cursorY - 1, COLOR_SEPARATOR);
        cursorY += 2;

        // 属性行：名称左对齐，数值右对齐，保证数值列整齐。
        int valueColumnX = x + panelWidth - PADDING - panel.valueWidth();
        for (Row row : rows) {
            graphics.drawString(font, row.name(), x + PADDING, cursorY, COLOR_NAME, true);
            graphics.drawString(font, row.value(), valueColumnX, cursorY, COLOR_VALUE, true);
            cursorY += LINE_HEIGHT;
        }
    }

    // ==================================================================
    // 面板缓存
    // ==================================================================

    /**
     * 缓存的面板内容。
     *
     * @param rows       属性行
     * @param values     构成这些行的原始数值，用于判断是否需要重建
     * @param nameWidth  名称列最大宽度
     * @param valueWidth 数值列最大宽度
     */
    private record CachedPanel(List<Row> rows, double[] values, int nameWidth, int valueWidth) {
    }

    /** 上一次构建的面板；为 null 表示尚无缓存。 */
    private static CachedPanel cachedPanel = null;

    /**
     * 星辉（Astral Sorcery）当前为玩家提供的额外暴击数值。
     *
     * <p>它们不属于原版属性，只能主动去读；方法名带外部提示，
     * 避免与 {@link #readValues} 里的属性值混淆。
     */
    private static double externalCritChance = 0.0D;
    private static double externalCritDamage = 0.0D;

    /** 帧计数，用于把「取值比较」也降低到每若干帧一次。 */
    private static int frameCounter = 0;

    /**
     * 取值比较的间隔帧数。
     *
     * <p>数值最多每秒（服务端刷新间隔）变化一次，
     * 因此无需每帧都去读取属性；每 5 帧比较一次，
     * 即使在 60 FPS 下延迟也不足 0.1 秒，肉眼无法察觉。
     */
    private static final int VALUES_CHECK_INTERVAL_FRAMES = 5;

    /**
     * 取得当前应显示的面板内容（带缓存）。
     *
     * <h2>为什么需要缓存</h2>
     * GUI 每帧都会重绘。若每帧都重新读取属性、重新格式化文本并重新测量宽度，
     * 在数值根本没变的情况下全是白做的开销。
     *
     * <p>因此这里先取出构成面板的<b>原始数值</b>做比较：
     * 与上次完全一致时直接复用缓存的排版结果，
     * 只有确实变化了才重建行与宽度。
     *
     * @param player 本地玩家
     * @return 面板内容；无内容可显示时返回 null
     */
    private static CachedPanel getPanel(LocalPlayer player) {
        // 上一个面板存在时：每若干帧比较一次数值，未变化就直接复用。
        if (cachedPanel != null && frameCounter++ % VALUES_CHECK_INTERVAL_FRAMES != 0) {
            return cachedPanel;
        }

        double[] values = readValues(player);

        if (cachedPanel != null && Arrays.equals(cachedPanel.values(), values)) {
            // 数值没变：不重建、不重新排版、不重新测量。
            return cachedPanel;
        }

        List<Row> rows = new ArrayList<>();
        buildRows(rows, player);
        if (rows.isEmpty()) {
            cachedPanel = null;
            return null;
        }

        // 面板内容只在客户端存在，排查「面板显示不对」时无从下手。
        // 打开调试开关后把实际构建出来的每一行打出来，
        // 就能一眼分清是「取值不对」还是「渲染不对」。
        if (Config.LOG_ZONE_CALCULATION.getAsBoolean()) {
            StringBuilder dump = new StringBuilder();
            for (Row row : rows) {
                dump.append("\n    ").append(row.name()).append(" = ").append(row.value());
            }
            DamageModernization.LOGGER.info(
                    "[DM] stats panel rebuilt ({} rows):{}", rows.size(), dump);
            logRawValues(player);
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int nameWidth = 0;
        int valueWidth = 0;
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, font.width(row.name()));
            valueWidth = Math.max(valueWidth, font.width(row.value()));
        }

        cachedPanel = new CachedPanel(rows, values, nameWidth, valueWidth);
        return cachedPanel;
    }

    /**
     * 读取构成面板的全部原始数值。
     *
     * <p>只取值、不排版，因此非常轻量，适合频繁比较。
     *
     * @param player 本地玩家
     * @return 数值数组
     */
    private static double[] readValues(LocalPlayer player) {
        // 星辉的暴击 perk 不是原版属性，只能主动去读。
        // 放在这里而不是 buildRows，是为了让它也参与「数值是否变化」的比较——
        // 否则玩家在星辉里点出 perk 后，面板不会刷新，两边就对不上了。
        externalCritChance = AstralCompat.extraCritChance(player);
        externalCritDamage = AstralCompat.extraCritDamageBonus(player);

        return new double[] {
                // 生命值体系
                attributeValue(player, DMAttributes.BASE_HEALTH),
                attributeValue(player, DMAttributes.HEALTH_PERCENT),
                attributeValue(player, DMAttributes.HEALTH_FLAT),
                // 攻击力体系
                attributeValue(player, DMAttributes.BASE_ATTACK_POWER),
                attributeValue(player, DMAttributes.ATTACK_POWER_PERCENT),
                attributeValue(player, DMAttributes.ATTACK_POWER_FLAT),
                // 护甲体系
                attributeValue(player, DMAttributes.BASE_ARMOR),
                attributeValue(player, DMAttributes.ARMOR_PERCENT),
                attributeValue(player, DMAttributes.ARMOR_FLAT),
                attributeValue(player, DMAttributes.BASE_ARMOR_TOUGHNESS),
                attributeValue(player, DMAttributes.ARMOR_TOUGHNESS_PERCENT),
                attributeValue(player, DMAttributes.ARMOR_TOUGHNESS_FLAT),
                // 其余乘区
                attributeValue(player, DMAttributes.DAMAGE_AMPLIFIER),
                attributeValue(player, DMAttributes.DAMAGE_MULTIPLIER),
                attributeValue(player, DMAttributes.CRIT_CHANCE),
                attributeValue(player, DMAttributes.CRIT_DAMAGE),
                // 暴击区的加算子项，以及外部贡献（星辉）
                attributeValue(player, DMAttributes.CRIT_DAMAGE_BONUS),
                externalCritChance,
                externalCritDamage,
                // 增减伤区的分类型子项
                attributeValue(player, DMAttributes.PHYSICAL_AMPLIFIER),
                attributeValue(player, DMAttributes.MAGIC_AMPLIFIER),
                attributeValue(player, DMAttributes.PHYSICAL_RESISTANCE),
        };
    }

    /**
     * 读取属性值；属性不存在时返回 0。
     *
     * @param player    本地玩家
     * @param attribute 属性
     * @return 属性值
     */
    private static double attributeValue(LocalPlayer player, Holder<Attribute> attribute) {
        return player.getAttribute(attribute) == null ? 0.0D : player.getAttributeValue(attribute);
    }

    /**
     * 打印护甲/韧性相关属性的<b>原始内部值</b>，用于排查「面板显示 0」。
     *
     * <p>关键是能区分两种情形：
     * <ul>
     *   <li>客户端 {@code base_armor} 基础值与总值都是 0，但原版 {@code armor} 有值
     *       → 同步没到，问题在服务端或同步；</li>
     *   <li>两者都有值 → 问题在取值或渲染。</li>
     * </ul>
     *
     * @param player 本地玩家
     */
    private static void logRawValues(LocalPlayer player) {
        DamageModernization.LOGGER.info(
                "[DM] raw armor state (client): "
                        + "vanilla_armor base={} total={}, "
                        + "base_armor base={} total={} modifiers={}, "
                        + "armor_percent={}, armor_flat={}, "
                        + "base_toughness base={} total={} modifiers={}",
                baseValueOf(player, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
                attributeValue(player, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
                baseValueOf(player, DMAttributes.BASE_ARMOR),
                attributeValue(player, DMAttributes.BASE_ARMOR),
                modifierCount(player, DMAttributes.BASE_ARMOR),
                attributeValue(player, DMAttributes.ARMOR_PERCENT),
                attributeValue(player, DMAttributes.ARMOR_FLAT),
                baseValueOf(player, DMAttributes.BASE_ARMOR_TOUGHNESS),
                attributeValue(player, DMAttributes.BASE_ARMOR_TOUGHNESS),
                modifierCount(player, DMAttributes.BASE_ARMOR_TOUGHNESS));
    }

    /**
     * {@return 属性的基础值；属性不存在时返回 -1}
     *
     * @param player    本地玩家
     * @param attribute 属性
     */
    private static double baseValueOf(LocalPlayer player, Holder<Attribute> attribute) {
        var instance = player.getAttribute(attribute);
        return instance == null ? -1.0D : instance.getBaseValue();
    }

    /**
     * {@return 属性上的修饰符数量；属性不存在时返回 -1}
     *
     * @param player    本地玩家
     * @param attribute 属性
     */
    private static int modifierCount(LocalPlayer player, Holder<Attribute> attribute) {
        var instance = player.getAttribute(attribute);
        return instance == null ? -1 : instance.getModifiers().size();
    }

    /**
     * {@return 外部加成（如星辉的 perk）在该属性上的<b>加算</b>总量}
     *
     * <p>这些修饰符直接挂在原版属性上，属于「提升值」而不是基础值。
     * 兜底读原版属性时要把它们减掉，否则会被算进基础值。
     *
     * <p>只统计加法修饰符：乘算类在提升值那边本来就是按百分比折算的，
     * 这里做减法会把语义搞乱，因此不参与扣减。
     *
     * @param player    本地玩家
     * @param attribute 原版属性
     */
    private static double externalAdditiveOf(LocalPlayer player, Holder<Attribute> attribute) {
        var instance = player.getAttribute(attribute);
        if (instance == null) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (var modifier : instance.getModifiers()) {
            if (AttributeMirror.isExternal(modifier)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum += modifier.amount();
            }
        }
        return sum;
    }

    /** 清空缓存。 */
    private static void clearCache() {
        cachedPanel = null;
    }

    /**
     * 收集要显示的全部属性行。
     *
     * <p>每个属性被拆成「基础值」与「提升值」两部分：
     * <ul>
     *   <li>基础值 = {@code AttributeInstance.getBaseValue()}，
     *       由原版攻击伤害迁移或属性默认值决定；</li>
     *   <li>提升值 = 总值 − 基础值，即所有修饰符叠加后的净贡献。</li>
     * </ul>
     *
     * @param player 本地玩家
     * @param font   字体，用于后续宽度计算
     * @return 属性行列表
     */
    private static void buildRows(List<Row> rows, LocalPlayer player) {
        // 「有基础值的成长体系」都显示「结果（基础值 + 非基础值）」。
        // 它们形状相同：结果 = 基础值 × (1 + 百分比) + 固定值。
        // 四种体系一律带客户端兜底，保证同步缺失时也不会画成 0。
        addBasePlusBonusRow(rows, player,
                DMAttributes.BASE_HEALTH,
                DMAttributes.HEALTH_PERCENT,
                DMAttributes.HEALTH_FLAT,
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                com.vestudio.dmmod.damage.HealthFormulaEvaluator.maxHealthModifierId(),
                attributeName(DMAttributes.BASE_HEALTH));

        // 护甲与盔甲韧性：与生命值同一套逻辑、同一套兜底。
        addBasePlusBonusRow(rows, player,
                DMAttributes.BASE_ARMOR,
                DMAttributes.ARMOR_PERCENT,
                DMAttributes.ARMOR_FLAT,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                com.vestudio.dmmod.damage.ArmorFormulaEvaluator.armorModifierId(),
                attributeName(DMAttributes.BASE_ARMOR));

        addBasePlusBonusRow(rows, player,
                DMAttributes.BASE_ARMOR_TOUGHNESS,
                DMAttributes.ARMOR_TOUGHNESS_PERCENT,
                DMAttributes.ARMOR_TOUGHNESS_FLAT,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS,
                com.vestudio.dmmod.damage.ArmorFormulaEvaluator.toughnessModifierId(),
                attributeName(DMAttributes.BASE_ARMOR_TOUGHNESS));

        addAttackPowerZoneRow(rows, player);

        // 其余乘区没有分开的基础值，只显示「结果（增加量）」；
        // 没有增加量时连括号一并省略。
        addBonusOnlyRow(rows, player, DMAttributes.DAMAGE_AMPLIFIER);
        addBonusOnlyRow(rows, player, DMAttributes.DAMAGE_MULTIPLIER);

        // 增减伤区的分类型子项：按伤害类型各占一行，而不是合并成一个数——
        // 它们是否计入取决于本次伤害的类型（打物理时算物理那条），
        // 合成一个静态数字在语义上是不成立的。
        addBonusOnlyRow(rows, player, DMAttributes.PHYSICAL_AMPLIFIER);
        addBonusOnlyRow(rows, player, DMAttributes.MAGIC_AMPLIFIER);
        addBonusOnlyRow(rows, player, DMAttributes.PHYSICAL_RESISTANCE);

        // 暴击区的两行要按「实际生效的口径」合成，不能只读单个属性：
        // 暴击率还要加上外部来源，暴击伤害还要加上加算子项与外部来源。
        addCritChanceRow(rows, player);
        addCritDamageRow(rows, player);
    }

    /**
     * {@return 属性的显示名}
     *
     * @param attribute 属性
     */
    private static String attributeName(Holder<Attribute> attribute) {
        return Component.translatable(attribute.value().getDescriptionId()).getString();
    }

    /**
     * 生成「暴击率」这一行。
     *
     * <p>实际掷骰用的是「属性 + 外部来源」（见
     * {@link com.vestudio.dmmod.damage.DamageEventHandler}），
     * 因此这里也按同一口径合计，否则面板会与真实战斗结果错位。
     *
     * @param rows   结果列表
     * @param player 本地玩家
     */
    private static void addCritChanceRow(List<Row> rows, LocalPlayer player) {
        Holder<Attribute> attribute = DMAttributes.CRIT_CHANCE;
        if (player.getAttribute(attribute) == null) {
            return;
        }

        double total = StatFormat.critChanceTotal(
                player.getAttributeValue(attribute), externalCritChance);
        double delta = total - attribute.value().getDefaultValue();

        String name = Component.translatable(attribute.value().getDescriptionId()).getString();
        rows.add(new Row(name, StatFormat.bonusOnlyValue(attribute, total, delta)));
    }

    /**
     * 生成「暴击伤害」这一行。
     *
     * <p>暴击区的实际倍率是四项之和（见 {@link StatFormat#critDamageTotal}）：
     * <pre>
     *   倍率本体 + 加算子项 + 全局加成 + 外部来源
     * </pre>
     * 过去这里只显示倍率本体 {@code crit_damage}，因此武器给的「暴击伤害加成」
     * （例如重锤的 +30%）和星辉的 perk 都看不见；现在按暴击区真实口径合计。
     *
     * @param rows   结果列表
     * @param player 本地玩家
     */
    private static void addCritDamageRow(List<Row> rows, LocalPlayer player) {
        Holder<Attribute> attribute = DMAttributes.CRIT_DAMAGE;
        if (player.getAttribute(attribute) == null) {
            return;
        }

        double total = StatFormat.critDamageTotal(
                player.getAttributeValue(attribute),
                attributeValue(player, DMAttributes.CRIT_DAMAGE_BONUS),
                Config.getDoubleOr(Config.CRIT_ZONE_DAMAGE_BONUS, 0.0D),
                externalCritDamage);

        // 基准取属性的默认值（默认为 1.5），因此没有加成时不显示括号。
        double delta = total - attribute.value().getDefaultValue();

        String name = Component.translatable(attribute.value().getDescriptionId()).getString();
        rows.add(new Row(name, StatFormat.bonusOnlyValue(attribute, total, delta)));
    }

    /**
     * 生成「结果（基础值 + 非基础值）」形式的乘区行。
     *
     * <p>适用于有<b>可成长基础值</b>的体系——生命值、护甲、盔甲韧性、攻击力。
     * 它们都是同一个形状：
     * <pre>
     *   结果 = 基础值 × (1 + 百分比) + 固定值
     *   非基础值 = 结果 − 基础值
     * </pre>
     * 基础值本身会随装备（甚至武器）变化，因此单独列出来，
     * 比只给一个结果更能说明「这个加成加在哪一层」。
     *
     * <h2>为什么取「总值」而不是 getBaseValue()</h2>
     * 装备带来的贡献是以<b>修饰符</b>形式挂在这个属性上的，
     * 它们同样属于「基础值」，因此读总值才是对的。
     *
     * <h2>fallbackAttr：客户端侧的兜底</h2>
     * 面板是<b>客户端</b>渲染的，而这些属性由服务端算好再同步过来。
     * 一旦自定义属性在客户端还没拿到同步值（显示成 0），
     * 面板就会把基础值画成 0——而其实客户端本地就有可用的原版数据。
     * 传入对应的原版属性作为兜底即可（见 {@link #deriveBaseFromVanilla}）。
     *
     * @param rows           结果列表
     * @param player         本地玩家
     * @param baseAttr       基础值属性（同时决定数值的显示格式）
     * @param percentAttr    百分比属性
     * @param flatAttr       固定值属性
     * @param fallbackAttr   兜底用的原版属性；不需要时传 {@code null}
     * @param ourModifierId  本 mod 写回该原版属性时用的修饰符 id；没有传 {@code null}
     * @param name           行名
     */
    private static void addBasePlusBonusRow(List<Row> rows,
                                            LocalPlayer player,
                                            Holder<Attribute> baseAttr,
                                            Holder<Attribute> percentAttr,
                                            Holder<Attribute> flatAttr,
                                            @Nullable Holder<Attribute> fallbackAttr,
                                            @Nullable ResourceLocation ourModifierId,
                                            String name) {
        // 基础值属性缺失时不显示该行（属性被其他模组移除的极端情况）。
        if (player.getAttribute(baseAttr) == null) {
            return;
        }

        double base = player.getAttributeValue(baseAttr);
        // 只在自定义属性读出来是 0 时才回退——那种情形几乎一定是同步还没到。
        // 不能用 max：原版属性里还包含我们写回的差值，取 max 会把「结果」当成「基础值」。
        if (fallbackAttr != null && StatFormat.isZero(base)) {
            base = deriveBaseFromVanilla(player, fallbackAttr, ourModifierId);
        }

        double percent = attributeValue(player, percentAttr);
        double flat = attributeValue(player, flatAttr);

        double total = base * (1.0D + percent) + flat;
        double nonBase = total - base;

        rows.add(new Row(name, StatFormat.basePlusBonus(baseAttr, total, base, nonBase)));
    }

    /**
     * 从原版属性反推「基础值」，用于同步缺失时的兜底。
     *
     * <p>原版属性的总值由四部分构成：
     * <pre>
     *   总值 = 原版基础值 + 装备等加成 + 外部加成 + 本 mod 写回的差值
     * </pre>
     * 因此反推时要把后两项<b>都减掉</b>：
     * <ul>
     *   <li>外部加成（如星辉的 perk）属于提升值，已经在 {@code *_flat} 那边算过一份；</li>
     *   <li>本 mod 写回的那一份是「结果 − 原版总值」，不减就会把结果当成基础值。</li>
     * </ul>
     *
     * <p>只统计<b>加法</b>项：乘算类在提升值那边是按百分比折算的，
     * 在这里做减法会把语义搞乱。
     *
     * @param player         本地玩家
     * @param vanillaAttr    原版属性
     * @param ourModifierId  本 mod 写回时用的修饰符 id；没有传 {@code null}
     * @return 反推出的基础值（不小于 0）
     */
    private static double deriveBaseFromVanilla(LocalPlayer player,
                                                Holder<Attribute> vanillaAttr,
                                                @Nullable ResourceLocation ourModifierId) {
        double total = attributeValue(player, vanillaAttr);
        double external = externalAdditiveOf(player, vanillaAttr);

        double ours = 0.0D;
        var instance = player.getAttribute(vanillaAttr);
        if (instance != null && ourModifierId != null) {
            var modifier = instance.getModifier(ourModifierId);
            if (modifier != null) {
                ours = modifier.amount();
            }
        }

        return StatFormat.deriveBase(total, external, ours);
    }

    /**
     * 生成「攻击力区」这一行。
     *
     * <p>该乘区由三个属性共同决定：{@code 基础攻击力}、{@code 攻击力百分比提升}、
     * {@code 固定攻击力}。三者同属一个乘区，因此合并为<b>一行</b>展示。
     *
     * <h2>显示格式</h2>
     * <pre>
     *   攻击力   结果（基础攻击力 + 非基础攻击力）
     * </pre>
     *
     * <h2>基础攻击力包含武器</h2>
     * 武器的攻击伤害会被换算并计入基础攻击力（空手 1、钻石剑 7），
     * 因此加号<b>前面</b>的是手持当前武器后的基础攻击力，
     * 加号<b>后面</b>才是非基础的那部分（百分比提升与固定攻击力带来的点数）。
     *
     * <p>行名用专门的键（「攻击力」），与属性名「基础攻击力」区分开——
     * 这一行展示的是整个乘区的结果，而不只是那个属性。
     *
     * @param rows   结果列表
     * @param player 本地玩家
     */
    private static void addAttackPowerZoneRow(List<Row> rows, LocalPlayer player) {
        // 攻击力同样带兜底：原版 attack_damage 由客户端按手持武器算出来。
        // 我们没有往它身上写回结果（是反过来把武器加成镜像进基础攻击力），
        // 因此没有「自己的修饰符」要减。
        addBasePlusBonusRow(rows, player,
                DMAttributes.BASE_ATTACK_POWER,
                DMAttributes.ATTACK_POWER_PERCENT,
                DMAttributes.ATTACK_POWER_FLAT,
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                null,
                Component.translatable("gui." + DamageModernization.MODID + ".attack_power_zone")
                        .getString());
    }

    /**
     * 生成「只显示增加量」的乘区行。
     *
     * <p>适用于没有独立基础值的乘区（伤害提升、伤害倍率、暴击率、暴击伤害）：
     * <pre>
     *   结果（增加量）
     * </pre>
     * 增加量以百分比呈现且不带 {@code +} 号；<b>数值没有变动时省略括号</b>。
     *
     * @param rows      结果列表
     * @param player    本地玩家
     * @param attribute 属性
     */
    private static void addBonusOnlyRow(List<Row> rows,
                                        LocalPlayer player,
                                        Holder<Attribute> attribute) {
        if (player.getAttribute(attribute) == null) {
            return;
        }

        double total = player.getAttributeValue(attribute);
        // 以属性默认值为基准判断「是否变动」：
        // 例如伤害倍率默认 1.0、暴击率默认 5%，没有加成时就不显示括号。
        double baseline = attribute.value().getDefaultValue();
        // 默认值为 0 时「总值」本身就是全部增加量，括号只会把同一个数字重复一遍
        // （例如「物理伤害提升 50%（50%）」），因此这种情况直接省略括号。
        double delta = StatFormat.isZero(baseline) ? 0.0D : total - baseline;

        String name = Component.translatable(attribute.value().getDescriptionId()).getString();

        // 排版为「结果（增加量）」；无变动时省略括号。
        String value = StatFormat.bonusOnlyValue(attribute, total, delta);

        rows.add(new Row(name, value));
    }

    /**
     * 面板中的一行。
     *
     * @param name  属性名称
     * @param value 格式化后的数值文本
     */
    private record Row(String name, String value) {
    }
}
