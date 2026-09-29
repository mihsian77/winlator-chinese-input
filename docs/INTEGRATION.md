# 集成指南

本文说明如何把 `winlator-chinese-input` 集成到你的 Winlator 分支中。

---

## 一、在 bionic-plus（或其他 Winlator 7.x 分支）中集成

### 1. 引入库模块

把本仓库的 `library/` 目录整体复制到你工程根目录，然后：

**`settings.gradle`**
```gradle
rootProject.name = "你的工程名"
include ':app'
include ':library'   // 新增
```

**app 模块 `build.gradle`**
```gradle
dependencies {
    implementation project(':library')
}
```

### 2. 实现 XServerBridge

`XServerBridge` 是本库与你工程 XServer 之间的唯一契约。下面是一个对接 Winlator
风格 XServer 的示例（方法名按你工程实际 XServer 类调整）：

```java
public class WinlatorXServerBridge implements XServerBridge {
    private final XServer xServer; // 你工程里的 XServer 实例

    public WinlatorXServerBridge(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public void sendKeyEvent(int keyCode, int keysym, boolean pressed) {
        // 调用你工程中发送按键事件的方法。常见签名类似：
        // xServer.sendKeyEvent(keyCode, pressed);
        // 若你的 XServer 直接以 keysym 驱动，则把 keysym 一并传入。
        xServer.sendKeyEvent(keyCode, pressed);
    }

    @Override
    public int getKeySym(int keyCode) {
        // 读取键位表中该 keyCode 当前的 keysym
        return xServer.getKeyMapping(keyCode);
    }

    @Override
    public void setKeySym(int keyCode, int keysym) {
        // 改写键位表
        xServer.setKeyMapping(keyCode, keysym);
    }

    @Override
    public boolean isAvailable() {
        return xServer != null && xServer.isRunning();
    }
}
```

> 不同分支的 XServer 方法名可能不同（`sendKeyCode` / `injectKey` / `keyAction` 等），
> 按实际签名对接即可。核心是三件事：**发键、读键符、写键符**。

### 3. 在 Activity 中持有并使用

在 `XServerDisplayActivity` 中：

```java
private ChineseInputPro chineseInput;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    XServerBridge bridge = new WinlatorXServerBridge(getXServer());
    chineseInput = new ChineseInputPro(bridge);
    chineseInput.setDebugEnabled(true);
    chineseInput.setCallback(new InputCallback() {
        @Override public void onInputStarted(int total) {}
        @Override public void onCharProcessed(char ch, int i, int total) {}
        @Override public void onInputComplete(boolean success) {}
        @Override public void onError(String msg) { Log.e("cn", msg); }
    });
}

// 拦截 Ctrl+V 粘贴
@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (chineseInput.interceptPasteShortcut(event, this)) {
        return true; // 已消费粘贴事件
    }
    return super.onKeyDown(keyCode, event);
}

@Override
protected void onDestroy() {
    chineseInput.release();
    super.onDestroy();
}
```

### 4. 接入 IME 提交文本

在你自定义输入法/输入框的 `InputConnection.commitText()` 回调里：

```java
@Override
public boolean commitText(CharSequence text, int newCursorPosition) {
    chineseInput.enqueueString(text.toString());
    return true; // 已接管，不再走默认通道
}
```

---

## 二、在其他 Winlator 分支中集成

1. 同上引入 `library` 模块；
2. 关键是**对齐键位表**：先确认你分支 XServer 里小键盘键的真实 keyCode，
   再通过 `chineseInput.setKeyPool(intArray)` 覆盖默认值；
3. 用 `setDebugEnabled(true)` 打开日志，观察每字符注入的 keyCode / keysym 是否符合预期。

---

## 三、常见问题排查

| 现象 | 排查方向 |
|------|----------|
| 完全没反应 | `XServerBridge.isAvailable()` 是否返回 true；日志是否打印"XServer 不可用" |
| 丢字 / 断字 | 调大 `setCharDelayMs`（如 12~20ms）；或改用 `ChineseInputPro` 粘贴模式 |
| 输入乱码 | 检查 `setKeySym` 是否真的改写了键位表；keysym 是否为 `0x01000000\|codepoint` |
| 某游戏按键失灵 | 该游戏可能用到了默认小键盘键，用 `setKeyPool()` 换成该游戏不用的键 |
| ANR / 卡顿 | 确认没有在主线程调用 `enqueueString` 之外的阻塞方法；本库内部已用后台线程 |
| 重复输入 | `handleAndroidKeyEvent` / `commitText` 返回 true 后，确保不再把事件转发给 Winlator |
