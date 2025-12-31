package com.github.abjfh;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * BitTrie单元测试
 *
 * <p>测试内容：
 * <ul>
 *   <li>基本插入和查找
 *   <li>最长前缀匹配
 *   <li>边界情况
 *   <li>异常处理
 * </ul>
 */
public class BitTrieTest {

    private BitTrie<String> trie;

    @Before
    public void setUp() {
        trie = new BitTrie<>();
    }

    // ==================== 辅助方法 ====================

    private byte[] ipToBytes(String ipStr) {
        String[] parts = ipStr.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    // ==================== 基本功能测试 ====================

    @Test
    public void testEmptyTrie() {
        assertNull("Empty trie should return null", trie.get(ipToBytes("1.2.3.4")));
    }

    @Test
    public void testSinglePrefix() {
        trie.put(ipToBytes("10.0.0.0"), 8, "AS10");

        assertEquals("AS10", trie.get(ipToBytes("10.1.2.3")));
        assertEquals("AS10", trie.get(ipToBytes("10.255.255.255")));
        assertNull(trie.get(ipToBytes("11.0.0.1")));
    }

    @Test
    public void testMultiplePrefixes() {
        trie.put(ipToBytes("10.0.0.0"), 8, "AS10");
        trie.put(ipToBytes("192.168.0.0"), 16, "AS192");
        trie.put(ipToBytes("8.8.8.0"), 24, "AS8");

        assertEquals("AS10", trie.get(ipToBytes("10.1.2.3")));
        assertEquals("AS192", trie.get(ipToBytes("192.168.1.1")));
        assertEquals("AS8", trie.get(ipToBytes("8.8.8.1")));
        assertNull(trie.get(ipToBytes("1.2.3.4")));
    }

    // ==================== 最长前缀匹配测试 ====================

    @Test
    public void testLongestPrefixMatch() {
        trie.put(ipToBytes("10.0.0.0"), 8, "broad");
        trie.put(ipToBytes("10.1.0.0"), 16, "narrow");
        trie.put(ipToBytes("10.1.2.0"), 24, "narrower");

        assertEquals("narrower", trie.get(ipToBytes("10.1.2.100")));
        assertEquals("narrower", trie.get(ipToBytes("10.1.2.255")));
        assertEquals("narrow", trie.get(ipToBytes("10.1.3.1")));
        assertEquals("broad", trie.get(ipToBytes("10.2.3.4")));
    }

    @Test
    public void testPartiallyOverlappingPrefixes() {
        trie.put(ipToBytes("192.168.1.0"), 24, "net1");
        trie.put(ipToBytes("192.168.2.0"), 24, "net2");
        trie.put(ipToBytes("192.168.0.0"), 16, "default");

        assertEquals("net1", trie.get(ipToBytes("192.168.1.100")));
        assertEquals("net2", trie.get(ipToBytes("192.168.2.100")));
        assertEquals("default", trie.get(ipToBytes("192.168.3.1")));
    }

    // ==================== 边界情况测试 ====================

    @Test
    public void testSlashZero() {
        trie.put(ipToBytes("0.0.0.0"), 0, "default");

        assertEquals("default", trie.get(ipToBytes("1.2.3.4")));
        assertEquals("default", trie.get(ipToBytes("255.255.255.255")));
    }

    @Test
    public void testSlashThirtyTwo() {
        trie.put(ipToBytes("1.2.3.4"), 32, "host");

        assertEquals("host", trie.get(ipToBytes("1.2.3.4")));
        assertNull(trie.get(ipToBytes("1.2.3.5")));
    }

    @Test
    public void testBoundaryIPs() {
        trie.put(ipToBytes("0.0.0.0"), 0, "all");
        trie.put(ipToBytes("255.255.255.255"), 32, "broadcast");

        assertEquals("all", trie.get(ipToBytes("0.0.0.0")));
        assertEquals("broadcast", trie.get(ipToBytes("255.255.255.255")));
    }

    @Test
    public void testDifferentPrefixLengths() {
        // 测试所有可能的/1到/31前缀长度
        for (int len = 1; len <= 31; len++) {
            byte[] ip = ipToBytes("128.0.0.0");
            trie.put(ip, len, "prefix-" + len);

            // 验证该前缀范围内的IP能找到
            byte[] testIp = ipToBytes("128.0.0.1");
            assertEquals("Failed for /" + len, "prefix-" + len, trie.get(testIp));
        }
    }

    // ==================== 更新值测试 ====================

    @Test
    public void testUpdateValue() {
        trie.put(ipToBytes("10.0.0.0"), 8, "AS10");
        assertEquals("AS10", trie.get(ipToBytes("10.1.2.3")));

        trie.put(ipToBytes("10.0.0.0"), 8, "AS10-Updated");
        assertEquals("AS10-Updated", trie.get(ipToBytes("10.1.2.3")));
    }

    // ==================== 异常处理测试 ====================

    @Test(expected = IllegalArgumentException.class)
    public void testNullKey() {
        trie.put(null, 8, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullValue() {
        trie.put(ipToBytes("10.0.0.0"), 8, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativePrefixLength() {
        trie.put(ipToBytes("10.0.0.0"), -1, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPrefixLengthTooLarge() {
        trie.put(ipToBytes("10.0.0.0"), 33, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPrefixLengthExceedsKeyLength() {
        byte[] shortKey = new byte[2]; // 16位
        trie.put(shortKey, 17, "value"); // 17位超过16位
    }

    @Test
    public void testNullLookup() {
        assertNull(trie.get(null));
        assertNull(trie.get(new byte[0]));
    }

    // ==================== 复杂场景测试 ====================

    @Test
    public void testComplexRouteTable() {
        // 模拟真实路由表场景
        trie.put(ipToBytes("0.0.0.0"), 0, "default-route");
        trie.put(ipToBytes("10.0.0.0"), 8, "private-10");
        trie.put(ipToBytes("10.1.0.0"), 16, "vpn-10.1");
        trie.put(ipToBytes("172.16.0.0"), 12, "private-172");
        trie.put(ipToBytes("192.168.0.0"), 16, "private-192");
        trie.put(ipToBytes("8.8.8.0"), 24, "google-dns");
        trie.put(ipToBytes("1.1.1.0"), 24, "cloudflare-dns");

        assertEquals("vpn-10.1", trie.get(ipToBytes("10.1.2.3")));
        assertEquals("private-10", trie.get(ipToBytes("10.2.3.4")));
        assertEquals("private-172", trie.get(ipToBytes("172.16.1.1")));
        assertEquals("private-192", trie.get(ipToBytes("192.168.1.1")));
        assertEquals("google-dns", trie.get(ipToBytes("8.8.8.8")));
        assertEquals("cloudflare-dns", trie.get(ipToBytes("1.1.1.1")));
        assertEquals("default-route", trie.get(ipToBytes("9.9.9.9")));
    }

    @Test
    public void testValueReuse() {
        // 测试不同前缀可以存储相同的值
        String sharedValue = "shared-asn";

        trie.put(ipToBytes("10.0.0.0"), 8, sharedValue);
        trie.put(ipToBytes("192.168.0.0"), 16, sharedValue);
        trie.put(ipToBytes("172.16.0.0"), 12, sharedValue);

        assertEquals(sharedValue, trie.get(ipToBytes("10.1.2.3")));
        assertEquals(sharedValue, trie.get(ipToBytes("192.168.1.1")));
        assertEquals(sharedValue, trie.get(ipToBytes("172.16.1.1")));
    }

    // ==================== 位操作测试 ====================

    @Test
    public void testBitExactMatch() {
        // 测试特定位模式的精确匹配
        byte[] ip = {0x55, 0x55, 0x55, 0x55}; // 01010101 01010101 01010101 01010101
        trie.put(ip, 32, "exact");

        assertEquals("exact", trie.get(ip));

        // 修改一位，应该不匹配
        byte[] ip2 = {0x55, 0x55, 0x55, 0x54};
        assertNull(trie.get(ip2));
    }

    @Test
    public void testAlternatingBits() {
        // 测试不同长度的前缀
        for (int len = 1; len <= 31; len++) {
            // 为每个长度创建不同的Trie，避免覆盖
            BitTrie<String> testTrie = new BitTrie<>();
            byte[] ip = ipToBytes("128.0.0.0");
            testTrie.put(ip, len, "len-" + len);

            byte[] testIp = ipToBytes("128.0.0.1");
            assertEquals("Should match /" + len, "len-" + len, testTrie.get(testIp));
        }

        // /32需要精确匹配
        BitTrie<String> testTrie = new BitTrie<>();
        byte[] ip = ipToBytes("128.0.0.0");
        testTrie.put(ip, 32, "len-32");
        assertEquals("Should match /32 exactly", "len-32", testTrie.get(ipToBytes("128.0.0.0")));
        assertNull("Should not match different IP for /32", testTrie.get(ipToBytes("128.0.0.1")));
    }
}
