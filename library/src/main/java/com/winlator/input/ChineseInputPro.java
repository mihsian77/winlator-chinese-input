package com.winlator.input;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.view.KeyEvent;

/**
 * Winlator 中文输入增强版。
 *
 * <p>在 {@link ChineseInput} 基础上增加"粘贴模式"：</p>
 * <ul>
 *   <li>从系统剪贴板读取文本并整体入队，适用于 DNF / 乌龟服 / 传奇等
 *       <b>不接受逐字符快速注入</b>的游戏（这类游戏逐字发送会丢字或断字，
 *       一次性把成段文本送入队列反而更稳）；</li>
 *   <li>拦截 Ctrl+V 快捷键，自动粘贴剪贴板内容。</li>
 * </ul>
 */
public class ChineseInputPro extends ChineseInput {

    /** 粘贴时使用的额外延迟（毫秒），给不敏感游戏留出处理时间。 */
    private volatile int pasteDelayMs = 12;

    public ChineseInputPro(XServerBridge xServer) {
        super(xServer);
    }

    public ChineseInputPro(XServerBridge xServer, int queueCapacity) {
        super(xServer, queueCapacity);
    }

    /**
     * 设置粘贴模式下的字符延迟（会覆盖父类默认值）。
     *
     * @param delayMs 毫秒，范围 1~50
     */
    public void setPasteDelayMs(int delayMs) {
        super.setCharDelayMs(delayMs);
        this.pasteDelayMs = delayMs;
    }

    /**
     * 从系统剪贴板读取文本并入队粘贴。
     *
     * @param context Android 上下文，通常传 Activity
     * @return 实际粘贴的文本；剪贴板为空或非文本时返回空串
     */
    public String pasteFromClipboard(Context context) {
        if (context == null) return "";
        try {
            ClipboardManager cm =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item.getText();
            if (text == null) return "";
            String s = text.toString();
            // 粘贴前切换为更稳的延迟
            super.setCharDelayMs(pasteDelayMs);
            enqueueString(s);
            return s;
        } catch (Throwable t) {
            notifyError("读取剪贴板失败: " + t.getMessage());
            return "";
        }
    }

    /**
     * 拦截粘贴快捷键（Ctrl+V / Ctrl+Shift+V）。
     *
     * <p>应当在 View 的 onKeyDown / Activity 的 onKeyDown 中优先调用：
     * 若返回 true，表示事件已被消费（执行了粘贴），调用方应直接返回 true 不再转发。</p>
     *
     * @param event   Android 按键事件
     * @param context Android 上下文（用于读取剪贴板）
     * @return true 表示这是粘贴快捷键并已处理
     */
    public boolean interceptPasteShortcut(KeyEvent event, Context context) {
        if (event == null) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_V
                && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            pasteFromClipboard(context);
            return true;
        }
        return false;
    }
}
