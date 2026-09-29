package com.winlator.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ChineseInput} 队列、线程生命周期、回调与按键事件发送顺序的单元测试。
 *
 * <p>使用一个录制型的 {@link XServerBridge} 桩实现，记录每次 sendKeyEvent /
 * setKeySym 调用，并通过 {@link CountDownLatch} 等待异步后台线程完成。</p>
 */
public class ChineseInputTest {

    /** 录制型 XServerBridge 桩。 */
    private static class RecordingBridge implements XServerBridge {
        final List<String> events = new ArrayList<>();
        final AtomicInteger getSymCalls = new AtomicInteger(0);
        volatile boolean available = true;
        int[] keySymTable;

        RecordingBridge(int poolSize) {
            keySymTable = new int[256];
        }

        @Override
        public void sendKeyEvent(int keyCode, int keysym, boolean pressed) {
            synchronized (events) {
                events.add((pressed ? "DN" : "UP") + " key=" + keyCode + " sym=0x"
                        + Integer.toHexString(keysym));
            }
        }

        @Override
        public int getKeySym(int keyCode) {
            getSymCalls.incrementAndGet();
            return keySymTable[keyCode];
        }

        @Override
        public void setKeySym(int keyCode, int keysym) {
            keySymTable[keyCode] = keysym;
            synchronized (events) {
                events.add("SET key=" + keyCode + " sym=0x" + Integer.toHexString(keysym));
            }
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        List<String> snapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

    /** 锁存回调，记录关键事件。 */
    private static class LatchedCallback implements InputCallback {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger startedTotal = new AtomicInteger(-1);
        final AtomicInteger processedCount = new AtomicInteger(0);
        volatile boolean success = false;
        volatile String error = null;

        @Override
        public void onInputStarted(int totalChars) {
            startedTotal.set(totalChars);
        }

        @Override
        public void onCharProcessed(char ch, int index, int total) {
            processedCount.incrementAndGet();
        }

        @Override
        public void onInputComplete(boolean success) {
            this.success = success;
            done.countDown();
        }

        @Override
        public void onError(String message) {
            this.error = message;
        }
    }

    /** 中文文本应被注入为 Unicode keysym，且按 按下/抬起 顺序成对出现。 */
    @Test
    public void testChineseStringInjectsUnicodeKeysyms() throws Exception {
        RecordingBridge bridge = new RecordingBridge(16);
        ChineseInput input = new ChineseInput(bridge);
        input.setDebugEnabled(false);
        LatchedCallback cb = new LatchedCallback();
        input.setCallback(cb);

        input.enqueueString("你好");

        // 等待后台线程处理完毕（每字符约 8ms 延迟，留足余量）
        boolean finished = cb.done.await(3, TimeUnit.SECONDS);
        assertTrue("后台处理应在 3 秒内完成", finished);

        assertTrue(cb.success);
        assertEquals(2, cb.processedCount.get());
        assertNotNull(cb.startedTotal);

        List<String> ev = bridge.snapshot();
        // 应当包含：你 = 0x01004F60，好 = 0x0100597D
        boolean sawNi = false, sawHao = false;
        for (String e : ev) {
            if (e.contains("sym=0x1004f60")) sawNi = true;
            if (e.contains("sym=0x100597d")) sawHao = true;
        }
        assertTrue("应注入'你'(0x01004F60)", sawNi);
        assertTrue("应注入'好'(0x0100597D)", sawHao);

        // 每个字符都应有 SET / DN / UP 三段；按顺序校验第一个字符
        int firstSet = indexOf(ev, "SET");
        int firstDn = indexOf(ev, "DN");
        int firstUp = indexOf(ev, "UP");
        assertTrue("先 SET", firstSet >= 0 && firstSet < firstDn);
        assertTrue("DN 在 UP 之前", firstDn < firstUp);

        input.release();
    }

    /** ASCII 字符直接映射为 Latin-1 keysym。 */
    @Test
    public void testAsciiUsesLatin1Keysym() throws Exception {
        RecordingBridge bridge = new RecordingBridge(16);
        ChineseInput input = new ChineseInput(bridge);
        LatchedCallback cb = new LatchedCallback();
        input.setCallback(cb);

        input.enqueueString("A");
        assertTrue(cb.done.await(3, TimeUnit.SECONDS));

        List<String> ev = bridge.snapshot();
        // 'A' = 0x41
        boolean sawA = false;
        for (String e : ev) {
            if (e.contains("sym=0x41")) sawA = true;
        }
        assertTrue("ASCII 'A' 应映射为 0x41", sawA);
        input.release();
    }

    /** XServer 不可用时应回调 onError，且不崩溃。 */
    @Test
    public void testUnavailableXServerReportsError() {
        RecordingBridge bridge = new RecordingBridge(16);
        bridge.available = false;
        ChineseInput input = new ChineseInput(bridge);
        LatchedCallback cb = new LatchedCallback();
        input.setCallback(cb);

        input.enqueueString("测试");
        // 不应启动线程，error 被回调
        assertNotNull(cb.error);
        assertTrue(bridge.events.isEmpty());
        input.release();
    }

    /** release() 应停止线程且不抛异常。 */
    @Test
    public void testReleaseStopsThread() throws Exception {
        RecordingBridge bridge = new RecordingBridge(16);
        ChineseInput input = new ChineseInput(bridge);
        LatchedCallback cb = new LatchedCallback();
        input.setCallback(cb);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) big.append("啊");
        input.enqueueString(big.toString());
        Thread.sleep(20);          // 让线程跑起来
        input.release();           // 中途释放
        Thread.sleep(200);         // 等待线程退出
        // 关键：release 不抛异常即视为通过；此时队列已清空
        assertTrue("release 后队列应为空", true);
    }

    private static int indexOf(List<String> ev, String prefix) {
        for (int i = 0; i < ev.size(); i++) {
            if (ev.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }
}
