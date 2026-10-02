package com.vestudio.dmmod.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * 属性面板：独立 GUI 屏幕，顶部<b>一个按钮</b>在左右两块内容之间来回切换。
 *
 * <pre>
 *   ┌──────────────┬──────────────┐
 *   │  属性面板     │  属性来源     │   ← 两边都写出来，但整体是一个按钮，点哪都切换
 *   └──────────────┴──────────────┘
 *   左：数值构成（生存 / 输出 两栏）
 *   右：来源拆解（每个属性「哪来的」）
 * </pre>
 *
 * <p>内容超出可视区时可用<b>滚轮</b>上下滚动。
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
    private static final int COLOR_SEPARATOR = 0xFF3A3A44;

    private static final int COLOR_TAB_BG = 0xFF1E1E24;
    private static final int COLOR_TAB_HOVER = 0xFF2A3F5F;
    private static final int COLOR_TAB_ACTIVE = 0xFF33507A;
    private static final int COLOR_TAB_ON = 0xFFFFFFFF;
    private static final int COLOR_TAB_OFF = 0xFF8A8A98;

    private static final int COLOR_SCROLL_TRACK = 0x40000000;
    private static final int COLOR_SCROLL_THUMB = 0xFF6A6A78;

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
    private static final int SCROLL_STEP = 14;
    private static final int SCROLLBAR_WIDTH = 3;

    /** 面板的两块内容。 */
    private enum View {
        /** 数值构成。 */
        PANEL,
        /** 来源拆解。 */
        SOURCES;

        /**
         * {@return 点一下按钮之后应该显示的那一块}
         */
        View next() {
            return this == PANEL ? SOURCES : PANEL;
        }
    }

    private View view = View.PANEL;

    /** 「属性来源」视图内部的两种看法。 */
    private enum SourcesMode {
        /** 按属性：每个属性一行标题，下面是它的各个来源。 */
        BY_ATTRIBUTE,
        /** 按来源：每个来源一行标题，下面是它影响到的各个属性。 */
        BY_SOURCE;

        /**
         * {@return 点一下按钮之后应该显示的那一种}
         */
        SourcesMode next() {
            return this == BY_ATTRIBUTE ? BY_SOURCE : BY_ATTRIBUTE;
        }
    }

    private SourcesMode sourcesMode = SourcesMode.BY_ATTRIBUTE;

    /** 顶部切换按钮的屏幕区域，渲染时算出，供点击命中。 */
    private int tabX;
    private int tabY;
    private int tabW;
    private int tabH;

    /** 次级切换按钮（只在「属性来源」视图出现）的屏幕区域。 */
    private int subTabX;
    private int subTabY;
    private int subTabW;
    private int subTabH;
    private boolean subTabVisible;

    /** 内容滚动量（像素），0 表示顶部。 */
    private double scrollAmount;

    /** 本次渲染算出的内容总高度，用于钳制滚动。 */
    private int contentHeight;

    /** 本次渲染算出的可视区，用于裁剪与画滚动条。 */
    private int viewportTop;
    private int viewportBottom;
    private int viewportLeft;
    private int viewportRight;

    /** 面板视图的缓存。 */
    private List<Row> leftRows = List.of();
    private List<Row> rightRows = List.of();
    private double[] cachedValues;

    /** 来源视图本次渲染用的扫描结果（每帧只扫一次）。 */
    private List<AttributeSourceScanner.AttributeSources> sourcesCache = List.of();

    /** 来源视图本次渲染用的「按来源」分组（只在按来源模式下有值）。 */
    private List<OwnerGroup> ownerGroups = List.of();

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
     * 点顶部按钮切换到另一块内容。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @param button 按键
     * @return 是否已处理
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 次级按钮画在主按钮下方，两者不重叠；先判它只是为了语义清楚。
            if (subTabVisible
                    && mouseX >= subTabX && mouseX < subTabX + subTabW
                    && mouseY >= subTabY && mouseY < subTabY + subTabH) {
                sourcesMode = sourcesMode.next();
                scrollAmount = 0.0D;
                return true;
            }
            if (mouseX >= tabX && mouseX < tabX + tabW
                    && mouseY >= tabY && mouseY < tabY + tabH) {
                switchView(view.next());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 滚轮上下滚动内容。
     *
     * <p>只在内容确实超出可视区时才拦截，否则把事件交还给上层，
     * 避免面板「吃掉」滚轮却什么也没动。
     *
     * @param mouseX  鼠标 X
     * @param mouseY  鼠标 Y
     * @param scrollX 横向滚动量
     * @param scrollY 纵向滚动量（正数向上）
     * @return 是否已处理
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = maxScroll();
        if (max > 0) {
            scrollAmount = clampScroll(scrollAmount - scrollY * SCROLL_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 切换显示的内容，并把滚动位置归零。
     *
     * @param next 目标内容
     */
    private void switchView(View next) {
        if (view == next) {
            return;
        }
        view = next;
        scrollAmount = 0.0D;
    }

    /** {@return 当前允许的最大滚动量} */
    private int maxScroll() {
        return Math.max(0, contentHeight - (viewportBottom - viewportTop));
    }

    /**
     * 把滚动量钳制到合法范围。
     *
     * @param value 期望值
     * @return 钳制后的值
     */
    private double clampScroll(double value) {
        return Math.max(0.0D, Math.min(maxScroll(), value));
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
        drawTabButton(graphics, this.width / 2, top + LINE_HEIGHT + 4, mouseX, mouseY);

        // 「属性来源」视图里再叠一层「按属性 / 按来源」的次级切换
        int bodyTop = tabY + tabH + SECTION_GAP;
        subTabVisible = view == View.SOURCES;
        if (subTabVisible) {
            drawSubTabButton(graphics, this.width / 2, bodyTop, mouseX, mouseY);
            bodyTop += subTabH + 6;
        }

        // 可视区：按钮之下到屏幕底部
        viewportTop = bodyTop;
        viewportBottom = this.height - PADDING;
        viewportLeft = 0;
        viewportRight = this.width;
        if (viewportBottom <= viewportTop) {
            contentHeight = 0;
            return;
        }

        ensureRows(player);

        // 先算内容高度，才能钳制滚动量。
        if (view == View.PANEL) {
            contentHeight = measurePanelView();
        } else {
            sourcesCache = AttributeSourceScanner.scan(player);
            if (sourcesMode == SourcesMode.BY_ATTRIBUTE) {
                contentHeight = sourcesHeight(sourcesCache);
            } else {
                ownerGroups = groupByOwner(sourcesCache);
                contentHeight = ownerGroupsHeight(ownerGroups);
            }
        }

        scrollAmount = clampScroll(scrollAmount);
        int contentY = viewportTop - (int) scrollAmount;

        // 内容超出时裁剪，避免画到可视区之外。
        graphics.enableScissor(viewportLeft, viewportTop, viewportRight, viewportBottom);
        try {
            if (view == View.PANEL) {
                renderPanelView(graphics, left, contentY, contentW);
            } else {
                renderSourcesView(graphics, left, contentY, contentW);
            }
        } finally {
            graphics.disableScissor();
        }

        drawScrollBar(graphics);
    }

    /**
     * 画顶部那一个切换按钮。
     *
     * <p>视觉上<b>两边都写出来</b>（左「属性面板」、右「属性来源」），当前那一半加亮；
     * 但整个框是<b>一个</b>按钮、一个命中区——点哪边都只是切到另一块，
     * 而不是「点左半跳到左、点右半跳到右」。
     *
     * @param graphics 绘制上下文
     * @param centerX  水平中心
     * @param y        顶部 Y
     * @param mouseX   鼠标 X
     * @param mouseY   鼠标 Y
     */
    private void drawTabButton(GuiGraphics graphics, int centerX, int y, int mouseX, int mouseY) {
        Component left = Component.translatable(TAB_PANEL);
        Component right = Component.translatable(TAB_SOURCES);

        int half = Math.max(this.font.width(left), this.font.width(right)) + TAB_PAD * 2;
        int total = half * 2;
        int x = centerX - total / 2;

        tabX = x;
        tabY = y;
        tabW = total;
        tabH = TAB_HEIGHT;

        boolean hovered = mouseX >= x && mouseX < x + total
                && mouseY >= y && mouseY < y + TAB_HEIGHT;

        // 底板
        graphics.fill(x, y, x + total, y + TAB_HEIGHT,
                hovered ? COLOR_TAB_HOVER : COLOR_TAB_BG);

        // 当前那一半加亮
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

        // 文字：当前的高亮，另一个压暗
        int textY = y + (TAB_HEIGHT - this.font.lineHeight) / 2 + 1;
        graphics.drawString(this.font, left,
                x + (half - this.font.width(left)) / 2, textY,
                panelActive ? COLOR_TAB_ON : COLOR_TAB_OFF, true);
        graphics.drawString(this.font, right,
                x + half + (half - this.font.width(right)) / 2, textY,
                panelActive ? COLOR_TAB_OFF : COLOR_TAB_ON, true);
    }

    /**
     * 画「属性来源」视图里的次级切换按钮。
     *
     * <p>与顶部那个同款：两边都写出来、当前那半加亮，整体是一个命中区，
     * 点哪边都切到另一种看法。
     *
     * @param graphics 绘制上下文
     * @param centerX  水平中心
     * @param y        顶部 Y
     * @param mouseX   鼠标 X
     * @param mouseY   鼠标 Y
     */
    private void drawSubTabButton(GuiGraphics graphics, int centerX, int y, int mouseX, int mouseY) {
        Component left = Component.translatable(SUB_TAB_BY_ATTRIBUTE);
        Component right = Component.translatable(SUB_TAB_BY_SOURCE);

        int half = Math.max(this.font.width(left), this.font.width(right)) + TAB_PAD;
        int total = half * 2;
        int x = centerX - total / 2;
        int height = TAB_HEIGHT - 2;

        subTabX = x;
        subTabY = y;
        subTabW = total;
        subTabH = height;

        boolean hovered = mouseX >= x && mouseX < x + total
                && mouseY >= y && mouseY < y + height;

        graphics.fill(x, y, x + total, y + height,
                hovered ? COLOR_TAB_HOVER : COLOR_TAB_BG);

        boolean byAttribute = sourcesMode == SourcesMode.BY_ATTRIBUTE;
        if (byAttribute) {
            graphics.fill(x, y, x + half, y + height, COLOR_TAB_ACTIVE);
        } else {
            graphics.fill(x + half, y, x + total, y + height, COLOR_TAB_ACTIVE);
        }

        graphics.fill(x, y, x + total, y + 1, COLOR_BORDER);
        graphics.fill(x, y + height - 1, x + total, y + height, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + height, COLOR_BORDER);
        graphics.fill(x + total - 1, y, x + total, y + height, COLOR_BORDER);
        graphics.fill(x + half, y, x + half + 1, y + height, COLOR_BORDER);

        int textY = y + (height - this.font.lineHeight) / 2 + 1;
        graphics.drawString(this.font, left,
                x + (half - this.font.width(left)) / 2, textY,
                byAttribute ? COLOR_TAB_ON : COLOR_TAB_OFF, true);
        graphics.drawString(this.font, right,
                x + half + (half - this.font.width(right)) / 2, textY,
                byAttribute ? COLOR_TAB_OFF : COLOR_TAB_ON, true);
    }

    /**
     * 画右侧滚动条；不需要滚动时不画。
     *
     * @param graphics 绘制上下文
     */
    private void drawScrollBar(GuiGraphics graphics) {
        int max = maxScroll();
        int trackH = viewportBottom - viewportTop;
        if (max <= 0 || trackH <= 0) {
            return;
        }

        int barX = viewportRight - SCROLLBAR_WIDTH - 2;
        graphics.fill(barX, viewportTop, barX + SCROLLBAR_WIDTH, viewportBottom,
                COLOR_SCROLL_TRACK);

        int thumbH = Math.max(16, trackH * trackH / contentHeight);
        int travel = trackH - thumbH;
        int thumbY = viewportTop + (int) (travel * (scrollAmount / max));
        graphics.fill(barX, thumbY, barX + SCROLLBAR_WIDTH, thumbY + thumbH,
                COLOR_SCROLL_THUMB);
    }

    /**
     * {@return 「属性面板」视图的内容总高度}
     */
    private int measurePanelView() {
        int bodyRows = Math.max(leftRows.size(), rightRows.size());
        return PADDING * 3 + LINE_HEIGHT * 2 + SECTION_GAP + bodyRows * LINE_HEIGHT;
    }

    /**
     * {@return 「属性来源」视图的内容总高度}
     *
     * <p>必须与 {@link #renderSourcesView} 的行进方式完全一致，
     * 否则滚动条的滑块长度会和实际内容对不上。
     *
     * @param all 已扫描的来源列表
     */
    private static int sourcesHeight(List<AttributeSourceScanner.AttributeSources> all) {
        int height = PADDING * 2;
        for (var item : all) {
            height += LINE_HEIGHT;
            if (!StatFormat.isZero(item.base())) {
                height += LINE_HEIGHT;
            }
            height += item.sources().size() * LINE_HEIGHT;
            height += 4;
        }
        return height;
    }

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
     * 画「属性来源」视图。
     *
     * <p>两种看法共用同一个框，只是内容组织方向相反：
     * 「按属性」以属性为组头，「按来源」以来源为组头。
     *
     * @param graphics 绘制上下文
     * @param x        左边界
     * @param y        顶部
     * @param width    可用宽度
     */
    private void renderSourcesView(GuiGraphics graphics, int x, int y, int width) {
        if (sourcesMode == SourcesMode.BY_ATTRIBUTE) {
            renderByAttribute(graphics, x, y, width);
        } else {
            renderBySource(graphics, x, y, width);
        }
    }

    /**
     * 「按属性」：每个属性一行，下面是它的各个来源。
     *
     * @param graphics 绘制上下文
     * @param x        左边界
     * @param y        顶部
     * @param width    可用宽度
     */
    private void renderByAttribute(GuiGraphics graphics, int x, int y, int width) {
        List<AttributeSourceScanner.AttributeSources> all = sourcesCache;

        int panelW = sourcePanelWidth(width);
        int panelH = sourcesHeight(all);
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
     * 「按来源」：每个来源一行，下面是它影响到的各个属性。
     *
     * <p>与「按属性」是同一份数据换个方向看：那边回答「这个数值哪来的」，
     * 这边回答「这个东西给了我什么」。
     *
     * @param graphics 绘制上下文
     * @param x        左边界
     * @param y        顶部
     * @param width    可用宽度
     */
    private void renderBySource(GuiGraphics graphics, int x, int y, int width) {
        List<OwnerGroup> groups = ownerGroups;

        int panelW = sourcePanelWidth(width);
        int panelH = ownerGroupsHeight(groups);
        int px = Math.max(4, x + (width - panelW) / 2);
        drawFrame(graphics, px, y, panelW, panelH);

        int cursorY = y + PADDING;
        int right = px + panelW - PADDING;

        for (OwnerGroup group : groups) {
            // 来源名 + 它一共影响了几项
            graphics.drawString(this.font, group.owner(), px + PADDING, cursorY,
                    COLOR_SECTION, true);
            String count = String.valueOf(group.entries().size());
            graphics.drawString(this.font, count, right - this.font.width(count), cursorY,
                    COLOR_SOURCE_MUTED, false);
            cursorY += LINE_HEIGHT;

            for (OwnerEntry entry : group.entries()) {
                graphics.drawString(this.font, entry.attributeName(),
                        px + PADDING + SOURCE_INDENT, cursorY, COLOR_SOURCE_OWNER, false);
                String amount = amountText(entry.amount(), entry.operation());
                graphics.drawString(this.font, amount, right - this.font.width(amount), cursorY,
                        COLOR_VALUE, false);
                cursorY += LINE_HEIGHT;
            }

            cursorY += 4;
        }
    }

    /**
     * 把扫描结果按「来源」重新分组。
     *
     * <p>原版基础值不是修饰符，但它同样是一种来源，因此也归一组。
     * 分组顺序沿用扫描顺序（即属性顺序），保证每次打开位置稳定。
     *
     * @param all 扫描结果
     * @return 分组列表
     */
    private static List<OwnerGroup> groupByOwner(
            List<AttributeSourceScanner.AttributeSources> all) {
        Map<String, List<OwnerEntry>> grouped = new LinkedHashMap<>();

        for (var item : all) {
            if (!StatFormat.isZero(item.base())) {
                grouped.computeIfAbsent(Component.translatable(SOURCE_BASE).getString(),
                                key -> new ArrayList<>())
                        .add(new OwnerEntry(item.name(), item.base(), null));
            }
            for (var source : item.sources()) {
                grouped.computeIfAbsent(source.owner(), key -> new ArrayList<>())
                        .add(new OwnerEntry(item.name(), source.amount(), source.operation()));
            }
        }

        List<OwnerGroup> result = new ArrayList<>(grouped.size());
        for (var entry : grouped.entrySet()) {
            result.add(new OwnerGroup(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return result;
    }

    /**
     * {@return 「按来源」视图的内容总高度}
     *
     * <p>与 {@link #renderBySource} 的行进方式必须一致，否则滚动条会对不上。
     *
     * @param groups 分组
     */
    private static int ownerGroupsHeight(List<OwnerGroup> groups) {
        int height = PADDING * 2;
        for (OwnerGroup group : groups) {
            height += LINE_HEIGHT;
            height += group.entries().size() * LINE_HEIGHT;
            height += 4;
        }
        return height;
    }

    /**
     * {@return 来源视图的框宽}
     *
     * @param width 可用宽度
     */
    private int sourcePanelWidth(int width) {
        return Math.min(width + 80, this.width - 40);
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
                value(player, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE),
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
        // 这几个体系已改用 AS 模式：不再有独立的基础值属性，
        // 而是直接读原版属性的总值，再把「非基础值来源的那一份」剥出来当变化值。
        addVanillaBackedRow(left, player,
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                name(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH));
        addVanillaBackedRow(left, player,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                name(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR));
        addVanillaBackedRow(left, player,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS,
                name(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS));

        addVanillaBackedRow(right, player,
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                Component.translatable("gui." + DamageModernization.MODID + ".attack_power_zone").getString());

        // 攻击力自身的两个增量属性。
        // 外部 mod 搬进来的近战攻击力/近战伤害倍率就落在这两项上，
        // 不单独列出来的话它们只会混进「攻击力」的括号里，看不出是谁给的。
        addBonusOnlyRow(right, player, DMAttributes.ATTACK_POWER_PERCENT);
        addBonusOnlyRow(right, player, DMAttributes.ATTACK_POWER_FLAT);

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
     *   <li>「基础值」= 把<b>非基础值来源</b>的修饰符排除后按原版公式重算的值；</li>
     *   <li>两者之差就是变化值，显示在括号里。</li>
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
        double base = baseValueOnly(instance);

        rows.add(new Row(label, StatFormat.basePlusBonus(vanillaAttr, total, base, total - base)));
    }

    /**
     * 只统计「基础值」来源，按原版公式重算属性值。
     *
     * <p>原版公式为
     * {@code (基础值 + Σ加值) × (1 + Σ基础乘算) × Π(1 + 总乘算)}，
     * 这里逐项跳过<b>不属于基础值</b>的修饰符，
     * 得到「只有基础值来源参与时」的结果。哪些来源算基础值由配置决定
     * （见 {@code Config#isBaseValueNamespace}）。
     *
     * @param instance 属性实例
     * @return 只含基础值来源的结果
     */
    private static double baseValueOnly(
            net.minecraft.world.entity.ai.attributes.AttributeInstance instance) {
        double base = instance.getBaseValue();
        for (var modifier : instance.getModifiers()) {
            if (Config.isBaseValueNamespace(modifier.id().getNamespace())
                    && modifier.operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                base += modifier.amount();
            }
        }

        double result = base;
        for (var modifier : instance.getModifiers()) {
            if (Config.isBaseValueNamespace(modifier.id().getNamespace())
                    && modifier.operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                result += base * modifier.amount();
            }
        }
        for (var modifier : instance.getModifiers()) {
            if (Config.isBaseValueNamespace(modifier.id().getNamespace())
                    && modifier.operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                result *= 1.0D + modifier.amount();
            }
        }

        return result;
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
        return amountText(source.amount(), source.operation());
    }

    /**
     * {@return 某个来源对某个属性的贡献文本}
     *
     * <p>加值直接写数，乘算写成百分比；原版基础值没有运算方式，
     * 直接写数值（它是绝对值，不是增量）。
     *
     * @param amount    数值
     * @param operation 运算方式；{@code null} 表示绝对值
     */
    private static String amountText(double amount,
                                     net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
        if (operation == null) {
            return trim(amount);
        }
        String sign = amount >= 0.0D ? "+" : "";
        return switch (operation) {
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

    /** 「按来源」视图里的一个来源分组。 */
    private record OwnerGroup(String owner, List<OwnerEntry> entries) {
    }

    /** 分组里的一条：某个属性从该来源拿到了多少。 */
    private record OwnerEntry(String attributeName,
                              double amount,
                              net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
    }

    private static final String TAB_PANEL = "gui." + DamageModernization.MODID + ".stats_panel.tab_panel";
    private static final String TAB_SOURCES = "gui." + DamageModernization.MODID + ".stats_panel.tab_sources";
    private static final String SUB_TAB_BY_ATTRIBUTE =
            "gui." + DamageModernization.MODID + ".stats_panel.sources_by_attribute";
    private static final String SUB_TAB_BY_SOURCE =
            "gui." + DamageModernization.MODID + ".stats_panel.sources_by_source";
    private static final String SECTION_SURVIVAL = "gui." + DamageModernization.MODID + ".stats_panel.survival";
    private static final String SECTION_OFFENSE = "gui." + DamageModernization.MODID + ".stats_panel.offense";
    private static final String SOURCE_BASE = "gui." + DamageModernization.MODID + ".stats_panel.source_base";
}
