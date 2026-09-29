# 参考项目

本库的设计参考了社区内已有的 Winlator / ExaGear 中文输入方案。
以下项目在原理实现、键位选择、粘贴模式等方面提供了思路。

---

## 一、Winlator 生态内方案

### WowCNInput
- 面向 Winlator 的中文输入补丁，核心即"小键盘桩键 + Unicode keysym 注入"。
- 本库的键池选取与 keysym 改写流程参考其思路。

### GRW-CNChat
- 针对经典老游戏（传奇类）聊天框的中文输入补丁。
- 提供了"剪贴板整体粘贴"模式，本库 `ChineseInputPro` 借鉴了这一思路。

### Winlator 官方（brunodev85/winlator）
- 纯 Java XServer 的实现参考，`sendKeyEvent` / keymap 读写的对接依据。

---

## 二、ExaGear 方案

### ExaGear 中文输入法集成
- 在 ExaGear 模拟器中通过改写虚拟键盘键位实现中文注入的早期实践。
- 证明了"借用闲置键 -> 改写 keysym -> 发送键事件"在 ARM/x86 模拟层同样可行。

---

## 三、X11 协议参考

- X11 keysym 定义：ASCII/Latin-1 直接等同，Unicode 使用 `0x01000000 | codepoint`。
- 小键盘 keysym 范围：`XK_KP_0 = 0xFFB0` ~ `XK_KP_9 = 0xFFB9`，
  `XK_KP_Enter = 0xFF8D` 等。
- 键码与 keysym 分离：X Server 通过 keymap 表把 keycode 翻译成 keysym，
  因此"改键符"即可复用任意闲置键码。

---

## 四、相关常量速查

| 名称 | 值 |
|------|-----|
| `XK_Unicode base` | `0x01000000` |
| `XK_BackSpace` | `0xFF08` |
| `XK_Tab` | `0xFF09` |
| `XK_Return` | `0xFF0D` |
| `XK_Escape` | `0xFF1B` |
| `XK_Delete` | `0xFFFF` |
| `XK_KP_0` | `0xFFB0` |
| `XK_KP_9` | `0xFFB9` |

> 注：以上项目名称仅作原理参考，本库为独立实现，不直接复制其代码。
