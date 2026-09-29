package com.winlator.input;

/**
 * XServer 桥接接口。
 *
 * <p>本库不直接依赖 Winlator 的 XServer 具体实现，而是通过此接口抽象出与 X Server
 * 通信所需的最小能力。集成方（例如在 XServerDisplayActivity 中）需要用本接口包装
 * 真实的 XServer 实例，从而让本库可以在不同 Winlator 分支间移植。</p>
 *
 * <p>键码（keyCode）与键符（keysym）的含义遵循 X11 约定：</p>
 * <ul>
 *   <li>keyCode：X Server 内部的物理键码（8~255）。</li>
 *   <li>keysym：该键码当前映射到的符号，例如 ASCII 字符 'a' = 0x00000061，
 *       Unicode 字符通常使用 0x01000000 | codepoint 直接映射。</li>
 * </ul>
 */
public interface XServerBridge {

    /**
     * 发送一个按键事件（按下或抬起）。
     *
     * @param keyCode 要触发的 X11 键码
     * @param keysym  该键码当前对应的键符
     * @param pressed true 表示按下（KeyPress），false 表示抬起（KeyRelease）
     */
    void sendKeyEvent(int keyCode, int keysym, boolean pressed);

    /**
     * 读取某个键码当前映射的键符。
     *
     * @param keyCode X11 键码
     * @return 当前键符
     */
    int getKeySym(int keyCode);

    /**
     * 修改某个键码的键符映射（用于"桩键复用"：临时把一个闲置键改造成目标字符）。
     *
     * @param keyCode 要改写的 X11 键码
     * @param keysym  新的键符
     */
    void setKeySym(int keyCode, int keysym);

    /**
     * 检查 XServer 当前是否可用。
     *
     * @return true 表示可以安全发送事件
     */
    boolean isAvailable();
}
