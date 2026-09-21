package com.vestudio.dmmod.util;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * tooltip 行相关的纯工具方法。
 *
 * <p>刻意放在通用侧（不依赖任何客户端类型），
 * 以便服务端也能复用与测试。
 */
public final class TooltipLines {

    private TooltipLines() {
    }

    /**
     * {@return 行列表中是否存在使用了指定翻译键的行}
     *
     * @param lines 行列表
     * @param key   翻译键
     */
    public static boolean anyContainsKey(List<Component> lines, String key) {
        for (Component line : lines) {
            if (containsKey(line, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return 是否存在任何一行包含指定翻译键或该键作为字面文本}
     *
     * <p>用于幂等判断：既要认出翻译键形式，也要认出已被解析成文本的形式。
     *
     * @param lines 行列表
     * @param key   翻译键，同时用作字面文本的匹配目标
     */
    public static boolean anyContains(List<Component> lines, String key) {
        for (Component line : lines) {
            if (containsKey(line, key) || line.getString().contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归判断组件自身或其任意兄弟节点是否使用了指定翻译键。
     *
     * <p>原版的属性行形如 {@code Component.literal(" ").append(text)}，
     * 可翻译内容位于<b>兄弟节点</b>而非顶层，
     * 因此只看 {@code getContents()} 是找不到的，必须递归。
     *
     * @param component 组件
     * @param key       翻译键
     * @return 是否包含
     */
    public static boolean containsKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents contents
                && key.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsKey(sibling, key)) {
                return true;
            }
        }
        return false;
    }
}
