package com.winlator.input;

import android.util.Log;
import android.view.KeyEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Winlator 中文输入基础版。
 *
 * <p>核心原理：利用 X11 的 16 个小键盘键（KP_0~KP_9 等）作为"桩键"池。这些键在绝大多数
 * 游戏 / 办公软件中不会被使用，因此可以临时借用：</p>
 * <ol>
 *   <li>从键池借一个空闲键码；</li>
 *   <li>保存它原本的 keysym；</li>
 *   <li>把它的 keysym 改写为目标字符（ASCII 直接用 Latin-1 keysym，
 *       Unicode 用 0x01000000 | codepoint）；</li>
 *   <li>发送 KeyPress，短暂延迟后发送 KeyRelease；</li>
 *   <li>恢复原 keysym 并把键归还键池。</li>
 * </ol>
 *
 * <p>字符在后台线程串行处理，避免阻塞 UI 线程造成 ANR；队列有界（默认 1024），
 * 防止快速连续输入导致内存膨胀。所有 XServer 调用前都会做空指针与可用性检查，
 * processChar 全程 catch(Throwable)，异常不会逃逸到处理线程之外。</p>
 */
public class ChineseInput {

    private static final String TAG = "ChineseInput";

    /** 默认队列容量。 */
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** 处理线程队列轮询空闲等待时间（毫秒）。 */
    private static final long IDLE_POLL_MS = 200L;

    /** Unicode 直接映射 keysym 的高位标志：XK_Unicode = 0x01000000。 */
    private static final int XK_UNICODE_BASE = 0x01000000;

    /** X11 特殊键符常量。 */
    private static final int XK_BackSpace = 0xFF08;
    private static final int XK_Tab       = 0xFF09;
    private static final int XK_Return    = 0xFF0D;
    private static final int XK_Escape    = 0xFF1B;
    private static final int XK_Delete   = 0xFFFF;

    /**
     * 桩键池：16 个小键盘键的 X11 键码。
     *
     * <p>取值遵循 X.Org evdev 标准键位（input event code + 8）。若所集成的 Winlator
     * 分支中 XServer 的键位表不同，请通过 {@link #setKeyPool(int[])} 覆盖为实际键码。</p>
     */
    private static int[] KEY_POOL = new int[]{
            90, // KP_0  (XK_KP_0   = 0xFFB0)
            87, // KP_1  (XK_KP_1   = 0xFFB1)
            88, // KP_2  (XK_KP_2   = 0xFFB2)
            89, // KP_3  (XK_KP_3   = 0xFFB3)
            83, // KP_4  (XK_KP_4   = 0xFFB4)
            84, // KP_5  (XK_KP_5   = 0xFFB5)
            85, // KP_6  (XK_KP_6   = 0xFFB6)
            79, // KP_7  (XK_KP_7   = 0xFFB7)
            80, // KP_8  (XK_KP_8   = 0xFFB8)
            81, // KP_9  (XK_KP_9   = 0xFFB9)
            91, // KP_Decimal (0xFFAE)
            106,// KP_Divide  (0xFFAF)
            63, // KP_Multiply(0xFFAA)
            82, // KP_Subtract(0xFFAD)
            86, // KP_Add     (0xFFAB)
            104 // KP_Enter   (0xFF8D)
    };

    /** 键池占用标记（与 {@link #KEY_POOL} 下标一一对应）。 */
    private final boolean[] occupied = new boolean[KEY_POOL.length];

    /** 字符队列（有界）。 */
    private final BlockingQueue<Character> inputQueue;

    /** XServer 桥接。 */
    private final XServerBridge xServer;

    /** 处理线程运行状态。 */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /** 同步锁：保护 processThread 生命周期与键池借还。 */
    private final Object lock = new Object();

    /** 当前处理线程。 */
    private volatile Thread processThread;

    /** 输入回调。 */
    private volatile InputCallback callback;

    /** 累计待处理字符数（用于回调统计）。 */
    private volatile int totalChars = 0;

    /** 已处理字符计数。 */
    private volatile int processedChars = 0;

    /** 字符按下与抬起之间的延迟（毫秒），范围 1~50。 */
    private volatile int charDelayMs = 8;

    /** 调试日志开关。 */
    private volatile boolean debugEnabled = false;

    /**
     * 构造中文输入器。
     *
     * @param xServer XServer 桥接，不能为 null
     */
    public ChineseInput(XServerBridge xServer) {
        this(xServer, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * 构造中文输入器，可指定队列容量。
     *
     * @param xServer      XServer 桥接
     * @param queueCapacity 队列容量（&gt;0）
     */
    public ChineseInput(XServerBridge xServer, int queueCapacity) {
        this.xServer = xServer;
        int cap = queueCapacity > 0 ? queueCapacity : DEFAULT_QUEUE_CAPACITY;
        this.inputQueue = new LinkedBlockingQueue<>(cap);
    }

    // ---------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------

    /**
     * 设置回调。
     */
    public void setCallback(InputCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置字符延迟（按下与抬起之间的间隔）。
     *
     * @param delayMs 毫秒，会被限制在 1~50 之间
     */
    public void setCharDelayMs(int delayMs) {
        if (delayMs < 1) delayMs = 1;
        if (delayMs > 50) delayMs = 50;
        this.charDelayMs = delayMs;
    }

    /**
     * 开关调试日志。
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    /**
     * 覆盖默认的桩键池（当所集成分支的 XServer 键位不同时使用）。
     *
     * @param pool 新的键码数组，长度建议 16；为 null 或空则忽略
     */
    public synchronized void setKeyPool(int[] pool) {
        if (pool == null || pool.length == 0) return;
        KEY_POOL = pool.clone();
        // 重建占用表
        for (int i = 0; i < occupied.length; i++) occupied[i] = false;
    }

    /**
     * 处理一个 Android 按键事件：若其 Unicode 字符是可打印 ASCII，则入队注入。
     *
     * <p>注意：调用方在返回 true 时应当消费掉该事件（不再转发给 Winlator），
     * 否则会重复输入。</p>
     *
     * @param event Android 按键事件
     * @return true 表示本方法已接管该字符（已入队）；false 表示未处理
     */
    public boolean handleAndroidKeyEvent(KeyEvent event) {
        if (event == null) return false;
        // 仅在按下时注入一次，避免重复
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int ch = event.getUnicodeChar();
        if (ch >= 0x20 && ch < 0x7F) {
            enqueueString(String.valueOf((char) ch));
            return true;
        }
        return false;
    }

    /**
     * 把一段文本逐字符入队，并启动后台处理线程（若尚未运行）。
     *
     * @param text 待注入文本，可为 null 或空
     */
    public void enqueueString(String text) {
        if (text == null || text.length() == 0) return;

        if (xServer == null || !xServer.isAvailable()) {
            loge("XServer 不可用，丢弃输入", null);
            InputCallback cb = callback;
            if (cb != null) cb.onError("XServer 不可用");
            return;
        }

        int added = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 有界队列：offer 失败说明队列已满，停止继续入队
            if (inputQueue.offer(c)) {
                added++;
            } else {
                loge("输入队列已满，丢弃后续字符", null);
                InputCallback cb = callback;
                if (cb != null) cb.onError("输入队列已满");
                break;
            }
        }

        if (added == 0) return;
        totalChars += added;

        InputCallback cb = callback;
        if (cb != null) cb.onInputStarted(totalChars);

        startProcessorIfNeeded();
    }

    /**
     * 字符到 X11 keysym 的映射（纯函数，可单独测试）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>可打印 ASCII（0x20~0x7E）：X11 Latin-1 keysym 与 ASCII 码相同，直接返回；</li>
     *   <li>常用控制字符（回车/退格/制表/ESC/Delete）：返回对应 XK_ 常量；</li>
     *   <li>其他 Unicode 字符：返回 0x01000000 | codepoint。</li>
     * </ul>
     *
     * @param ch 字符
     * @return X11 keysym；无法映射的控制字符返回 0
     */
    public static int mapToXKeySym(char ch) {
        // 可打印 ASCII（含空格）：Latin-1 直接等同
        if (ch >= 0x20 && ch < 0x7F) {
            return ch;
        }
        // 常用控制键映射
        switch (ch) {
            case '\b': return XK_BackSpace;
            case '\t': return XK_Tab;
            case '\n':
            case '\r': return XK_Return;
            case '\u001B': return XK_Escape;
            case '\u007F': return XK_Delete;
            default: break;
        }
        // Unicode：直接映射 keysym
        return XK_UNICODE_BASE | ch;
    }

    /**
     * 停止处理线程、清空队列、释放资源。
     */
    public void release() {
        processing.set(false);
        Thread t = processThread;
        if (t != null) {
            t.interrupt();
        }
        inputQueue.clear();
        totalChars = 0;
        processedChars = 0;
        log("release() 已调用");
    }

    /**
     * 供子类使用：向上层回调一条错误信息。
     *
     * @param message 错误描述
     */
    protected void notifyError(String message) {
        InputCallback cb = callback;
        if (cb != null) cb.onError(message);
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    /** 若处理线程未运行则启动。 */
    private void startProcessorIfNeeded() {
        synchronized (lock) {
            if (processThread == null || !processThread.isAlive()) {
                processing.set(true);
                Thread t = new Thread(this::processLoop, "winlator-cn-input");
                processThread = t;
                t.start();
            }
        }
    }

    /** 后台处理主循环。 */
    private void processLoop() {
        log("处理线程启动");
        InputCallback cb = callback;
        boolean success = true;
        try {
            while (true) {
                Character ch;
                try {
                    ch = inputQueue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    success = false;
                    break;
                }
                if (ch == null) {
                    // 在锁内确认队列确实为空后才退出，避免与 enqueueString 竞争丢字
                    synchronized (lock) {
                        if (inputQueue.isEmpty()) {
                            processing.set(false);
                            processThread = null; // 与退出判定原子化
                            break;
                        }
                    }
                    continue;
                }

                processedChars++;
                int index = processedChars;
                try {
                    processChar(ch);
                } catch (Throwable t) {
                    // 全程 catch(Throwable)：单个字符失败不影响后续字符
                    loge("处理字符失败: " + ch, t);
                    if (cb != null) cb.onError(t.getMessage());
                }
                if (cb != null) {
                    cb.onCharProcessed(ch, index, totalChars);
                }
            }
            if (cb != null) cb.onInputComplete(success);
        } catch (Throwable t) {
            loge("处理线程异常退出", t);
            if (cb != null) cb.onInputComplete(false);
        } finally {
            synchronized (lock) {
                // 异常退出兜底：若尚未把线程置空则补上
                if (processThread == Thread.currentThread()) {
                    processThread = null;
                }
            }
            log("处理线程结束");
        }
    }

    /**
     * 处理单个字符：借用桩键 -> 改写 keysym -> 按下/抬起 -> 恢复。
     *
     * @param ch 目标字符
     * @throws Throwable 处理过程中的任意异常都会向上抛到 processLoop 统一记录
     */
    private void processChar(char ch) throws Throwable {
        if (xServer == null || !xServer.isAvailable()) {
            throw new IllegalStateException("XServer 不可用");
        }

        int keysym = mapToXKeySym(ch);
        int borrowedKey = acquireKey();
        if (borrowedKey < 0) {
            // 极端情况下所有键都被占用：短暂等待后重试一次
            Thread.sleep(20);
            borrowedKey = acquireKey();
        }
        if (borrowedKey < 0) {
            throw new IllegalStateException("桩键池已全部占用");
        }

        int savedKeysym = xServer.getKeySym(borrowedKey);
        try {
            xServer.setKeySym(borrowedKey, keysym);
            // 按下
            xServer.sendKeyEvent(borrowedKey, keysym, true);
            // 短暂延迟，确保 XServer / Wine 能识别为独立击键
            Thread.sleep(charDelayMs);
            // 抬起
            xServer.sendKeyEvent(borrowedKey, keysym, false);
            if (debugEnabled) {
                log(String.format("注入 char='%s' keysym=0x%08X key=%d", ch, keysym, borrowedKey));
            }
        } finally {
            // 无论成功失败都恢复原 keysym 并归还键
            try {
                xServer.setKeySym(borrowedKey, savedKeysym);
            } catch (Throwable ignore) {
                // 恢复失败不抛，避免掩盖原始异常
            }
            releaseKey(borrowedKey);
        }
    }

    /** 从键池借一个空闲键码；全部占用时返回 -1。 */
    private synchronized int acquireKey() {
        for (int i = 0; i < KEY_POOL.length; i++) {
            if (!occupied[i]) {
                occupied[i] = true;
                return KEY_POOL[i];
            }
        }
        return -1;
    }

    /** 归还键码到键池。 */
    private synchronized void releaseKey(int keyCode) {
        for (int i = 0; i < KEY_POOL.length; i++) {
            if (KEY_POOL[i] == keyCode) {
                occupied[i] = false;
                return;
            }
        }
    }

    /** 调试日志（开关可控；在单元测试环境下 android.util.Log 不可用，吞掉异常）。 */
    private void log(String msg) {
        if (!debugEnabled) return;
        try {
            Log.d(TAG, msg);
        } catch (Throwable ignore) {
            // 单元测试（plain JVM）中 android.util.Log 是桩实现，忽略
        }
    }

    /** 错误日志。 */
    private void loge(String msg, Throwable t) {
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignore) {
            // 同上
        }
    }
}
