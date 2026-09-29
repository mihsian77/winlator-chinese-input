# winlator-chinese-input

Winlator 中文输入模块 —— 通过 **X11 小键盘桩键复用** 向 Wine 注入中文字符，
并提供剪贴板**粘贴模式**，适配绝大多数 Winlator 分支（bionic-plus、Winlator 7.x 等）。

---

## 目录

- [原理](#原理)
- [功能特性](#功能特性)
- [快速开始](#快速开始)
- [使用示例](#使用示例)
- [集成到 XServerDisplayActivity](#集成到-xserverdisplayactivity)
- [配置选项](#配置选项)
- [已知限制](#已知限制)
- [参考项目](#参考项目)
- [License](#license)

---

## 原理

Winlator 在 Android 端运行一个纯 Java 实现的 XServer，再由 Wine 连接它渲染 Windows 应用。
要输入中文，本质上是把"中文字符"变成 X11 键盘事件发给 Wine。

X11 有 16 个小键盘键（`KP_0`~`KP_9`、`KP_Enter`、`KP_Add` 等），它们在绝大多数游戏 /
软件中**不会被用到**。本模块把这些键当作"桩键池"：

1. 借一个空闲小键盘键；
2. 保存它原本的 keysym；
3. 把它的 keysym 临时改写成目标字符 —— ASCII 直接用 Latin-1 keysym，
   Unicode 用 `0x01000000 | codepoint`；
4. 发送 `KeyPress`，短暂延迟（默认 8ms），再发送 `KeyRelease`；
5. 恢复原 keysym，把键归还池。

字符在**后台线程**串行处理，队列有界（默认 1024），不阻塞 UI 线程、不会 ANR、不会内存膨胀。

---

## 功能特性

### 基础版 `ChineseInput`
- 小键盘桩键池（默认 16 键，可覆盖）
- 中英文混合输入：ASCII 走 Latin-1 keysym，中文走 Unicode 直接映射
- 后台线程 + 有界队列，避免 ANR
- 所有 XServer 调用前做 null / `isAvailable()` 检查
- `processChar` 全程 `catch(Throwable)`，单字符失败不影响后续
- 键池用 `boolean[]` 标记占用，杜绝键码冲突
- 字符延迟可配置（1~50ms，默认 8ms）
- 调试日志可开关

### 增强版 `ChineseInputPro`（继承 `ChineseInput`）
- `pasteFromClipboard(Context)`：从系统剪贴板整体粘贴，
  适用于 **DNF / 乌龟服 / 传奇** 等不接受逐字符快速注入的游戏
- `interceptPasteShortcut(KeyEvent, Context)`：拦截 **Ctrl+V** 自动粘贴

---

## 快速开始

### 方式一：作为 Gradle 依赖（发布后）

```gradle
dependencies {
    implementation 'com.github.mihsian77:winlator-chinese-input:1.0.0'
}
```

### 方式二：源码集成本库

1. 把 `library/` 模块复制进你的 Winlator 工程；
2. 在根目录 `settings.gradle` 中加入 `include ':library'`（或你命名的模块名）；
3. 在 app 模块 `build.gradle` 的 `dependencies` 中加入 `implementation project(':library')`。

详细步骤见 [docs/INTEGRATION.md](docs/INTEGRATION.md)。

---

## 使用示例

```java
// 1. 用你工程里的 XServer 实现包装出一个桥接（见集成指南）
XServerBridge bridge = new MyXServerBridge(xServer);

// 2. 创建输入器
ChineseInput input = new ChineseInput(bridge);
input.setDebugEnabled(true);
input.setCallback(new InputCallback() {
    @Override public void onInputStarted(int total) { /* 可选：提示开始 */ }
    @Override public void onCharProcessed(char ch, int i, int total) { /* 进度 */ }
    @Override public void onInputComplete(boolean success) { /* 完成 */ }
    @Override public void onError(String msg) { Log.e("cn", msg); }
});

// 3. 当 IME 提交一段中文时，直接入队
input.enqueueString("你好，世界！");

// 4. 增强版：粘贴模式
ChineseInputPro pro = new ChineseInputPro(bridge);
// 在 onKeyDown 里拦截 Ctrl+V：
// if (pro.interceptPasteShortcut(event, activity)) return true;
```

---

## 集成到 XServerDisplayActivity

1. 在你的 `XServerDisplayActivity`（或承载 XServer 的 Activity）里持有一个
   `ChineseInput` / `ChineseInputPro` 实例；
2. 实现 `XServerBridge`，把它对接到你工程的 XServer（发送 KeyPress/KeyRelease、
   读写 keymap）。参考示例见 [docs/INTEGRATION.md](docs/INTEGRATION.md)；
3. 在 IME 的 `onKey()` / `onCreateInputConnection` 拿到提交文本后调用
   `enqueueString(text)`；
4. 在 Activity 销毁时调用 `input.release()` 释放线程。

---

## 配置选项

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `setCharDelayMs(int)` | 8 | 按下/抬起间隔，范围 1~50ms。游戏丢字就调大，太快就调小 |
| `setDebugEnabled(boolean)` | false | 调试日志开关 |
| `setKeyPool(int[])` | 16 个小键盘键码 | 覆盖桩键池（当你分支的键位表不同时） |
| `setCallback(InputCallback)` | - | 设置输入过程回调 |
| `setPasteDelayMs(int)` | 12 | 增强版粘贴模式延迟 |

构造时可指定队列容量：`new ChineseInput(bridge, 2048)`。

---

## 已知限制

- **键位表差异**：默认键码遵循 X.Org evdev 标准（keycode = evdev code + 8）。
  若你的 Winlator 分支 XServer 键位不同，需通过 `setKeyPool()` 调整。
- **独占输入框**：少数游戏的输入框只接受硬件按键序列，逐字符注入可能丢字，
  请改用 `ChineseInputPro` 的粘贴模式。
- **补充平面字符**：当前按 BMP 单 `char` 处理；常见中文均在 BMP 内。
- **无第三方依赖**：仅依赖 Android SDK，未引入 Robolectric 等，单测在 plain JVM 上运行。

---

## 参考项目

详见 [docs/REFERENCE_PROJECTS.md](docs/REFERENCE_PROJECTS.md)。

---

## License

[MIT](LICENSE)
