package com.winlator.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link ChineseInput#mapToXKeySym(char)} 的键符映射单元测试。
 *
 * <p>纯函数测试，不依赖 Android 运行环境。</p>
 */
public class KeyMappingTest {

    /** 可打印 ASCII：X11 Latin-1 keysym 与 ASCII 码相同。 */
    @Test
    public void testAsciiPrintable() {
        // 小写字母
        assertEquals('a', ChineseInput.mapToXKeySym('a'));
        assertEquals('z', ChineseInput.mapToXKeySym('z'));
        // 大写字母
        assertEquals('A', ChineseInput.mapToXKeySym('A'));
        assertEquals('Z', ChineseInput.mapToXKeySym('Z'));
        // 数字
        assertEquals('0', ChineseInput.mapToXKeySym('0'));
        assertEquals('9', ChineseInput.mapToXKeySym('9'));
        // 空格与常见标点
        assertEquals(' ', ChineseInput.mapToXKeySym(' '));
        assertEquals('!', ChineseInput.mapToXKeySym('!'));
        assertEquals('@', ChineseInput.mapToXKeySym('@'));
        assertEquals('.', ChineseInput.mapToXKeySym('.'));
    }

    /** 常用控制字符映射到对应 XK_ 常量。 */
    @Test
    public void testControlChars() {
        assertEquals(0xFF08, ChineseInput.mapToXKeySym('\b')); // BackSpace
        assertEquals(0xFF09, ChineseInput.mapToXKeySym('\t')); // Tab
        assertEquals(0xFF0D, ChineseInput.mapToXKeySym('\n')); // Return
        assertEquals(0xFF0D, ChineseInput.mapToXKeySym('\r')); // Return
        assertEquals(0xFF1B, ChineseInput.mapToXKeySym('\u001B')); // Escape
        assertEquals(0xFFFF, ChineseInput.mapToXKeySym('\u007F')); // Delete
    }

    /** Unicode 字符：0x01000000 | codepoint。 */
    @Test
    public void testUnicodeChars() {
        // 你 U+4F60
        assertEquals(0x01000000 | 0x4F60, ChineseInput.mapToXKeySym('你'));
        // 好 U+597D
        assertEquals(0x01000000 | 0x597D, ChineseInput.mapToXKeySym('好'));
        // 中文标点：。U+3002
        assertEquals(0x01000000 | 0x3002, ChineseInput.mapToXKeySym('。'));
        // 汉字"中" U+4E2D
        assertEquals(0x01004E2D, ChineseInput.mapToXKeySym('中'));
    }

    /** 中文全角标点也走 Unicode 直接映射。 */
    @Test
    public void testChinesePunctuation() {
        // ，U+FF0C（全角逗号）
        assertEquals(0x0100FF0C, ChineseInput.mapToXKeySym('，'));
        // ！U+FF01
        assertEquals(0x0100FF01, ChineseInput.mapToXKeySym('！'));
        // ？U+FF1F
        assertEquals(0x0100FF1F, ChineseInput.mapToXKeySym('？'));
    }
}
