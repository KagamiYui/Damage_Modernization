package com.vestudio.dmmod.util;

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
