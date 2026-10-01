package com.vestudio.dmmod.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.vestudio.dmmod.Config;
import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.damage.AstralCompat;
import com.vestudio.dmmod.util.StatFormat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;

/**
 * 属性面板：独立 GUI 屏幕，顶部一个<b>分段切换按钮</b>在两块内容之间切换。
 *
 * <pre>
 *   ┌──────────────┬──────────────┐
 *   │  属性面板     │  属性来源     │   ← 一个控件、两半，当前那半高亮
 *   └──────────────┴──────────────┘
 *   左：数值构成（生存 / 输出 两栏）
 *   右：来源拆解（每个属性「哪来的」）
 * </pre>
 *
 * <p>按 <b>K</b> 打开，K 或 ESC 关闭。
 */
public final class StatsPanelScreen extends Screen {

    // ---- 配色 ----
    private static final int COLOR_MASK = 0xA0000000;
    private static final int COLOR_BACKGROUND = 0xF0121216;
    private static final int COLOR_BORDER = 0xFF5A5A66;
    private static final int COLOR_TITLE = 0xFFFFD479;
    private static final int COLOR_SECTION = 0xFF8AB4F8;
    private static final int COLOR_NAME = 0xFFE0E0E0;
    private static final int COLOR_VALUE = 0xFF8BE28B;
    private static final int COLOR_HINT = 0xFF7A7A88;
    private static final int COLOR_SEPARATOR = 0xFF3A3A44;

    private static final int COLOR_TAB_BG = 0xFF1E1E24;
    private static final int COLOR_TAB_ACTIVE = 0xFF33507A;
    private static final int COLOR_TAB_ON = 0xFFFFFFFF;
    private static final int COLOR_TAB_OFF = 0xFF8A8A98;

    private static final int COLOR_SOURCE_OWNER = 0xFFB8C4D0;
    private static final int COLOR_SOURCE_MUTED = 0xFF7A7A88;

    // ---- 布局 ----
    private static final int PADDING = 12;
    private static final int LINE_HEIGHT = 11;
    private static final int COLUMN_GAP = 28;
    private static final int SECTION_GAP = 14;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_PAD = 18;
    private static final int SOURCE_INDENT = 10;

    /** 面板的两块内容。 */
    private enum View {
        /** 数值构成。 */
        PANEL,
        /** 来源拆解。 */
        SOURCES
    }

    private View view = View.PANEL;

    /** 顶部切换按钮的屏幕区域，渲染时算出，供点击命中。 */
    private int tabX;
    private int tabY;
    private int tabW;
    private int tabH;

    /** 面板视图的缓存。 */
    private List<Row> leftRows = List.of();
    private List<Row> rightRows = List.of();
    private double[] cachedValues;

    /** 星辉提供的额外暴击数值（不属于原版属性）。 */
    private double externalCritChance;
    private double externalCritDamage;

    public StatsPanelScreen() {
        super(Component.translatable("gui." + DamageModernization.MODID + ".stats_panel.title"));
    }

    /**
     * 单人游戏时暂停。
     *
     * @return 是否暂停
     */
    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /**
     * 同一个按键也能关闭。
     *
     * @param keyCode   键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 是否已处理
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (StatsPanelKeybind.TOGGLE_PANEL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 点击顶部按钮的左半或右半来切换内容。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @param button 按键
     * @return 是否已处理
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= tabX && mouseX < tabX + tabW
                && mouseY >= tabY && mouseY < tabY + tabH) {
            view = mouseX < tabX + tabW / 2.0D ? View.PANEL : View.SOURCES;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 绘制整个界面。
     *
     * @param graphics    绘制上下文
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     * @param partialTick 插值刻度
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, COLOR_MASK);

        LocalPlayer player = this.minecraft == null ? null : this.minecraft.player;
        if (player == null) {
            return;
        }

        int contentW = Math.max(320, this.width / 2);
        int left = (this.width - contentW) / 2;
        int top = 18;

        // 标题
        Component title = getTitle();
        graphics.drawString(this.font, title, (this.width - this.font.width(title)) / 2, top,
                COLOR_TITLE, true);

        // 顶部切换按钮
        drawTabBar(graphics, this.width / 2, top + LINE_HEIGHT + 4);

        // 内容
        int bodyTop = tabY + tabH + SECTION_GAP;
        ensureRows(player);
        if (view == View.PANEL) {
            renderPanelView(graphics, left, bodyTop, contentW);
        } else {
            renderSourcesView(graphics, player, left, bodyTop, contentW);
        }

        // 底部提示
        Component hint = Component.translatable("gui." + DamageModernization.MODID + ".stats_panel.hint");
        graphics.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2,
                tabY + tabH + SECTION_GAP + bodyHeight() + 6, COLOR_HINT, false);
    }

    /**
     * 画顶部那一个分段按钮：一个外框、中间一条分线，激活的一半加亮。
     *
     * @param graphics 绘制上下文
     * @param centerX  水平中心
     * @param y        顶部 Y
     */
    private void drawTabBar(GuiGraphics graphics, int centerX, int y) {
        Component left = Component.translatable(TAB_PANEL);
        Component right = Component.translatable(TAB_SOURCES);

        int half = Math.max(this.font.width(left), this.font.width(right)) + TAB_PAD * 2;
        int total = half * 2;
        int x = centerX - total / 2;

        tabX = x;
        tabY = y;
        tabW = total;
        tabH = TAB_HEIGHT;

        // 底板
        graphics.fill(x, y, x + total, y + TAB_HEIGHT, COLOR_TAB_BG);

        // 激活的一半
        boolean panelActive = view == View.PANEL;
        if (panelActive) {
            graphics.fill(x, y, x + half, y + TAB_HEIGHT, COLOR_TAB_ACTIVE);
        } else {
            graphics.fill(x + half, y, x + total, y + TAB_HEIGHT, COLOR_TAB_ACTIVE);
        }

        // 边框与中分线
        graphics.fill(x, y, x + total, y + 1, COLOR_BORDER);
        graphics.fill(x, y + TAB_HEIGHT - 1, x + total, y + TAB_HEIGHT, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + TAB_HEIGHT, COLOR_BORDER);
        graphics.fill(x + total - 1, y, x + total, y + TAB_HEIGHT, COLOR_BORDER);
        graphics.fill(x + half, y, x + half + 1, y + TAB_HEIGHT, COLOR_BORDER);

        // 文字
        int textY = y + (TAB_HEIGHT - this.font.lineHeight) / 2 + 1;
        graphics.drawString(this.font, left,
                x + (half - this.font.width(left)) / 2, textY,
                panelActive ? COLOR_TAB_ON : COLOR_TAB_OFF, true);
        graphics.drawString(this.font, right,
                x + half + (half - this.font.width(right)) / 2, textY,
                panelActive ? COLOR_TAB_OFF : COLOR_TAB_ON, true);
    }

    /**
     * {@return 当前视图的内容高度，用于定位底部提示}
     */
    private int bodyHeight() {
        return view == View.PANEL
                ? Math.max(leftRows.size(), rightRows.size()) * LINE_HEIGHT + LINE_HEIGHT
                : sourceLineCount * LINE_HEIGHT;
    }

    /** 来源视图上一次渲染的行数，用于定位提示文字。 */
    private int sourceLineCount;

    /**
     * 画「属性面板」视图：生存 / 输出 两栏。
     *
     * @param graphics 绘制上下文
     * @param x        左边界
     * @param y        顶部
     * @param width    可用宽度
     */
    private void renderPanelView(GuiGraphics graphics, int x, int y, int width) {
        int leftNameW = maxNameWidth(leftRows);
        int leftValueW = maxValueWidth(leftRows);
        int rightNameW = maxNameWidth(rightRows);
        int rightValueW = maxValueWidth(rightRows);

        int leftW = leftNameW + COLUMN_GAP + leftValueW;
        int rightW = rightNameW + COLUMN_GAP + rightValueW;

        int bodyRows = Math.max(leftRows.size(), rightRows.size());
        int panelW = PADDING * 2 + leftW + COLUMN_GAP + rightW;
        int panelH = PADDING * 2 + LINE_HEIGHT * 2 + SECTION_GAP + bodyRows * LINE_HEIGHT;
        int px = Math.max(4, x + (width - panelW) / 2);

        drawFrame(graphics, px, y, panelW, panelH + PADDING);

        int leftX = px + PADDING;
        int bodyY = y + PADDING + LINE_HEIGHT + 4;
        graphics.drawString(this.font, Component.translatable(SECTION_SURVIVAL), leftX, bodyY - LINE_HEIGHT,
                COLOR_SECTION, true);
        drawRows(graphics, leftRows, leftX, bodyY, leftW);

        int rightX = leftX + leftW + COLUMN_GAP;
        graphics.drawString(this.font, Component.translatable(SECTION_OFFENSE), rightX, bodyY - LINE_HEIGHT,
                COLOR_SECTION, true);
        drawRows(graphics, rightRows, rightX, bodyY, rightW);
    }

    /**
     * 画「属性来源」视图：每个属性一行标题，下面缩进列出各来源。
     *
     * @param graphics 绘制上下文
     * @param player   本地玩家
     * @param x        左边界
     * @param y        顶部
     * @param width    可用宽度
     */
    private void renderSourcesView(GuiGraphics graphics, LocalPlayer player, int x, int y, int width) {
        List<AttributeSourceScanner.AttributeSources> all = AttributeSourceScanner.scan(player);

        int panelW = Math.min(width + 80, this.width - 40);
        int rows = 0;
        for (var item : all) {
            rows += 1 + (item.sources().isEmpty() ? 0 : item.sources().size());
        }
        sourceLineCount = rows + all.size();

        int panelH = PADDING * 2 + sourceLineCount * LINE_HEIGHT + all.size() * 2;
        int px = Math.max(4, x + (width - panelW) / 2);
        drawFrame(graphics, px, y, panelW, panelH);

        int cursorY = y + PADDING;
        int right = px + panelW - PADDING;

        for (var item : all) {
            // 属性名 + 总值
            graphics.drawString(this.font, item.name(), px + PADDING, cursorY, COLOR_SECTION, true);
            String total = trim(item.total());
            graphics.drawString(this.font, total, right - this.font.width(total), cursorY,
                    COLOR_VALUE, true);
            cursorY += LINE_HEIGHT;

            // 原版基础值（它不是修饰符，单独列一条）
            if (!StatFormat.isZero(item.base())) {
                graphics.drawString(this.font, Component.translatable(SOURCE_BASE).getString(),
                        px + PADDING + SOURCE_INDENT, cursorY, COLOR_SOURCE_MUTED, false);
                String base = trim(item.base());
                graphics.drawString(this.font, base, right - this.font.width(base), cursorY,
                        COLOR_SOURCE_MUTED, false);
                cursorY += LINE_HEIGHT;
            }

            // 各修饰符
            for (var source : item.sources()) {
                graphics.drawString(this.font, source.owner(), px + PADDING + SOURCE_INDENT, cursorY,
                        COLOR_SOURCE_OWNER, false);
                String amount = amountText(source);
                graphics.drawString(this.font, amount, right - this.font.width(amount), cursorY,
                        COLOR_VALUE, false);
                cursorY += LINE_HEIGHT;
            }

            // 空一行做组间分隔
            cursorY += 4;
        }
    }

    /**
     * 画一栏的属性行。
     *
     * @param graphics 绘制上下文
     * @param rows     行
     * @param x        栏左边界
     * @param y        起始 Y
     * @param colW     栏宽
     */
    private void drawRows(GuiGraphics graphics, List<Row> rows, int x, int y, int colW) {
        int cursorY = y;
        for (Row row : rows) {
            graphics.drawString(this.font, row.name(), x, cursorY, COLOR_NAME, true);
            graphics.drawString(this.font, row.value(), x + colW - this.font.width(row.value()),
                    cursorY, COLOR_VALUE, true);
            cursorY += LINE_HEIGHT;
        }
    }

    /**
     * 画底板与边框。
     *
     * @param graphics 绘制上下文
     * @param x        左上 X
     * @param y        左上 Y
     * @param w        宽
     * @param h        高
     */
    private void drawFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, COLOR_BACKGROUND);
        graphics.fill(x, y, x + w, y + 1, COLOR_BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + h, COLOR_BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, COLOR_BORDER);
        graphics.fill(x + PADDING, y + PADDING + LINE_HEIGHT, x + w - PADDING,
                y + PADDING + LINE_HEIGHT + 1, COLOR_SEPARATOR);
    }

    /**
     * 数值没变就复用上一次的行。
     *
     * @param player 本地玩家
     */
    private void ensureRows(LocalPlayer player) {
        double[] values = readValues(player);
        if (cachedValues != null && Arrays.equals(cachedValues, values) && !leftRows.isEmpty()) {
            return;
        }

        List<Row> left = new ArrayList<>();
        List<Row> right = new ArrayList<>();
        buildRows(left, right, player);

        leftRows = List.copyOf(left);
        rightRows = List.copyOf(right);
        cachedValues = values;

        if (Config.LOG_ZONE_CALCULATION.getAsBoolean()) {
            StringBuilder dump = new StringBuilder();
            for (Row row : leftRows) {
                dump.append("\n    [生存] ").append(row.name()).append(" = ").append(row.value());
            }
            for (Row row : rightRows) {
                dump.append("\n    [输出] ").append(row.name()).append(" = ").append(row.value());
            }
            DamageModernization.LOGGER.info("[DM] stats panel rebuilt:{}", dump);
        }
    }

    /**
     * 读取用于比较的原始数值。
     *
     * @param player 本地玩家
     * @return 数值数组
     */
    private double[] readValues(LocalPlayer player) {
        externalCritChance = AstralCompat.extraCritChance(player);
        externalCritDamage = AstralCompat.extraCritDamageBonus(player);

        return new double[] {
                value(player, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH),
                value(player, DMAttributes.HEALTH_PERCENT),
                value(player, DMAttributes.HEALTH_FLAT),
                value(player, DMAttributes.BASE_ATTACK_POWER),
                value(player, DMAttributes.ATTACK_POWER_PERCENT),
                value(player, DMAttributes.ATTACK_POWER_FLAT),
                value(player, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
                value(player, DMAttributes.ARMOR_PERCENT),
                value(player, DMAttributes.ARMOR_FLAT),
                value(player, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS),
                value(player, DMAttributes.ARMOR_TOUGHNESS_PERCENT),
                value(player, DMAttributes.ARMOR_TOUGHNESS_FLAT),
                value(player, DMAttributes.DAMAGE_AMPLIFIER),
                value(player, DMAttributes.DAMAGE_MULTIPLIER),
                value(player, DMAttributes.CRIT_CHANCE),
                value(player, DMAttributes.CRIT_DAMAGE),
                value(player, DMAttributes.CRIT_DAMAGE_BONUS),
                externalCritChance,
                externalCritDamage,
                value(player, DMAttributes.PHYSICAL_AMPLIFIER),
                value(player, DMAttributes.MAGIC_AMPLIFIER),
                value(player, DMAttributes.PHYSICAL_RESISTANCE),
        };
    }

    /**
     * 构建两栏的行。
     *
     * @param left   左栏
     * @param right  右栏
     * @param player 本地玩家
     */
    private void buildRows(List<Row> left, List<Row> right, LocalPlayer player) {
        // 这三个体系已改用 AS 模式：不再有独立的基础值属性，
        // 直接读原版属性的总值，再把「我们加的那一份」剥出来当变化值。
        addVanillaBackedRow(left, player,
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                name(DMAttributes.BASE_HEALTH));
        addVanillaBackedRow(left, player,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                name(DMAttributes.BASE_ARMOR));
        addVanillaBackedRow(left, player,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS,
                name(DMAttributes.BASE_ARMOR_TOUGHNESS));

        addVanillaBackedRow(right, player,
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                Component.translatable("gui." + DamageModernization.MODID + ".attack_power_zone").getString());
        addBonusOnlyRow(right, player, DMAttributes.DAMAGE_AMPLIFIER);
        addBonusOnlyRow(right, player, DMAttributes.DAMAGE_MULTIPLIER);
        addCritChanceRow(right, player);
        addCritDamageRow(right, player);
        addBonusOnlyRow(right, player, DMAttributes.PHYSICAL_AMPLIFIER);
        addBonusOnlyRow(right, player, DMAttributes.MAGIC_AMPLIFIER);
        addBonusOnlyRow(right, player, DMAttributes.PHYSICAL_RESISTANCE);
    }

    /**
     * 「结果（基础值 + 非基础值）」形式的行——直接读原版属性。
     *
     * <p>生命值、护甲、盔甲韧性已经改用 AS 模式：加成是以原版修饰符的形式挂上去的，
     * 不再有独立的基础值属性可读。因此这里：
     * <ul>
     *   <li>「结果」取原版属性的总值（原版已经把一切都算好了）；</li>
     *   <li>「基础值」= 把<b>本 mod 命名空间</b>的修饰符排除后按原版公式重算的值；</li>
     *   <li>两者之差就是我们的加成，显示在括号里。</li>
     * </ul>
     *
     * @param rows       目标栏
     * @param player     本地玩家
     * @param vanillaAttr 原版属性
     * @param label      行名
     */
    private void addVanillaBackedRow(List<Row> rows, LocalPlayer player,
                                     Holder<Attribute> vanillaAttr,
                                     String label) {
        var instance = player.getAttribute(vanillaAttr);
        if (instance == null) {
            return;
        }

        double total = instance.getValue();
        double base = withoutOurBonuses(instance);

        rows.add(new Row(label, StatFormat.basePlusBonus(vanillaAttr, total, base, total - base)));
    }

    /**
     * 排除本 mod 的修饰符后，按原版公式重算属性值。
     *
     * <p>原版公式为
     * {@code (基础值 + Σ加值) × (1 + Σ基础乘算) × Π(1 + 总乘算)}，
     * 这里逐项跳过属于本 mod 的修饰符，得到「没有我们参与时」的值。
     *
     * @param instance 属性实例
     * @return 不含本 mod 加成的值
     */
    private static double withoutOurBonuses(
            net.minecraft.world.entity.ai.attributes.AttributeInstance instance) {
        double base = instance.getBaseValue();
        for (var modifier : instance.getModifiers()) {
            if (!isOurs(modifier)
                    && modifier.operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                base += modifier.amount();
            }
        }

        double result = base;
        for (var modifier : instance.getModifiers()) {
            if (!isOurs(modifier)
                    && modifier.operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                result += base * modifier.amount();
            }
        }
        for (var modifier : instance.getModifiers()) {
            if (!isOurs(modifier)
                    && modifier.operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                result *= 1.0D + modifier.amount();
            }
        }

        return result;
    }

    /**
     * {@return 该修饰符是否由本 mod 写入}
     *
     * @param modifier 修饰符
     */
    private static boolean isOurs(
            net.minecraft.world.entity.ai.attributes.AttributeModifier modifier) {
        return modifier.id().getNamespace().equals(DamageModernization.MODID);
    }

    /**
     * 「结果（增加量）」形式的行。
     */
    private void addBonusOnlyRow(List<Row> rows, LocalPlayer player, Holder<Attribute> attribute) {
        if (player.getAttribute(attribute) == null) {
            return;
        }
        double total = player.getAttributeValue(attribute);
        double baseline = attribute.value().getDefaultValue();
        double delta = StatFormat.isZero(baseline) ? 0.0D : total - baseline;
        rows.add(new Row(name(attribute), StatFormat.bonusOnlyValue(attribute, total, delta)));
    }

    /**
     * 暴击率行。
     */
    private void addCritChanceRow(List<Row> rows, LocalPlayer player) {
        Holder<Attribute> attribute = DMAttributes.CRIT_CHANCE;
        if (player.getAttribute(attribute) == null) {
            return;
        }
        double total = StatFormat.critChanceTotal(player.getAttributeValue(attribute), externalCritChance);
        double delta = total - attribute.value().getDefaultValue();
        rows.add(new Row(name(attribute), StatFormat.bonusOnlyValue(attribute, total, delta)));
    }

    /**
     * 暴击伤害行。
     */
    private void addCritDamageRow(List<Row> rows, LocalPlayer player) {
        Holder<Attribute> attribute = DMAttributes.CRIT_DAMAGE;
        if (player.getAttribute(attribute) == null) {
            return;
        }
        double total = StatFormat.critDamageTotal(
                player.getAttributeValue(attribute),
                value(player, DMAttributes.CRIT_DAMAGE_BONUS),
                Config.getDoubleOr(Config.CRIT_ZONE_DAMAGE_BONUS, 0.0D),
                externalCritDamage);
        double delta = total - attribute.value().getDefaultValue();
        rows.add(new Row(name(attribute), StatFormat.bonusOnlyValue(attribute, total, delta)));
    }

    /**
     * {@return 来源的数值文本：加值直接写，乘算写成百分比}
     */
    private static String amountText(AttributeSourceScanner.Source source) {
        double amount = source.amount();
        String sign = amount >= 0.0D ? "+" : "";
        return switch (source.operation()) {
            case ADD_VALUE -> sign + trim(amount);
            case ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL -> sign + trim(amount * 100.0D) + "%";
        };
    }

    /**
     * {@return 去掉多余小数的数值文本}
     */
    private static String trim(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-6D) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 读属性值；属性不存在时返回 0。
     */
    private static double value(LocalPlayer player, Holder<Attribute> attribute) {
        return player.getAttribute(attribute) == null ? 0.0D : player.getAttributeValue(attribute);
    }

    /**
     * {@return 属性的显示名}
     */
    private static String name(Holder<Attribute> attribute) {
        return Component.translatable(attribute.value().getDescriptionId()).getString();
    }

    /** {@return 一栏里最宽的名称} */
    private int maxNameWidth(List<Row> rows) {
        int max = 0;
        for (Row row : rows) {
            max = Math.max(max, this.font.width(row.name()));
        }
        return max;
    }

    /** {@return 一栏里最宽的数值} */
    private int maxValueWidth(List<Row> rows) {
        int max = 0;
        for (Row row : rows) {
            max = Math.max(max, this.font.width(row.value()));
        }
        return max;
    }

    /** 面板中的一行。 */
    private record Row(String name, String value) {
    }

    private static final String TAB_PANEL = "gui." + DamageModernization.MODID + ".stats_panel.tab_panel";
    private static final String TAB_SOURCES = "gui." + DamageModernization.MODID + ".stats_panel.tab_sources";
    private static final String SECTION_SURVIVAL = "gui." + DamageModernization.MODID + ".stats_panel.survival";
    private static final String SECTION_OFFENSE = "gui." + DamageModernization.MODID + ".stats_panel.offense";
    private static final String SOURCE_BASE = "gui." + DamageModernization.MODID + ".stats_panel.source_base";
}
