package com.github.abjfh;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TrieToFPAConverter单元测试
 *
 * <p>测试内容：
 * <ul>
 *   <li>基本转换功能
 *   <li>层级结构正确性
 *   <li>稀疏vs稠密分支判断
 *   <li>边界情况
 * </ul>
 */
public class TrieToFPAConverterTest {

    private BitTrie<String[]> trie;

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

    private void addPrefix(String ip, int prefixLen, String[] value) {
        trie.put(ipToBytes(ip), prefixLen, value);
    }

    // ==================== 基本转换测试 ====================

    @Test
    public void testEmptyTrieConversion() {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        assertNotNull("FPA should not be null", fpa);
        assertEquals("Layer 1 should have 65536 entries", 65536, fpa.table.size());

        // 所有条目应该是相同的默认节点
        ForwardingPortArray.FPANode<String[]> first = fpa.table.get(0);
        for (int i = 0; i < 65536; i++) {
            assertEquals("All entries should be the same default node", first, fpa.table.get(i));
        }
    }

    @Test
    public void testSinglePrefixConversion() {
        addPrefix("10.0.0.0", 8, new String[]{"10.0.0.0/8", "AS10"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        assertNotNull("FPA should not be null", fpa);

        // 验证查找一致性
        verifyLookupConsistency(trie, fpa, "10.1.2.3");
        verifyLookupConsistency(trie, fpa, "10.255.255.255");
        verifyLookupConsistency(trie, fpa, "1.2.3.4");
    }

    @Test
    public void testMultiplePrefixesConversion() {
        addPrefix("10.0.0.0", 8, new String[]{"10.0.0.0/8", "AS10"});
        addPrefix("192.168.0.0", 16, new String[]{"192.168.0.0/16", "AS192"});
        addPrefix("8.8.8.0", 24, new String[]{"8.8.8.0/24", "AS8"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        // 验证多个IP的查找一致性
        verifyLookupConsistency(trie, fpa, "10.1.2.3");
        verifyLookupConsistency(trie, fpa, "192.168.1.1");
        verifyLookupConsistency(trie, fpa, "8.8.8.1");
        verifyLookupConsistency(trie, fpa, "1.2.3.4");
    }

    // ==================== 层级结构测试 ====================

    @Test
    public void testThreeLayerStructure() {
        // 创建一个三层结构的路由: /8 -> /16 -> /24
        addPrefix("10.0.0.0", 8, new String[]{"10.0.0.0/8", "L1"});
        addPrefix("10.1.0.0", 16, new String[]{"10.1.0.0/16", "L2"});
        addPrefix("10.1.2.0", 24, new String[]{"10.1.2.0/24", "L3"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        // 验证查找
        verifyLookupConsistency(trie, fpa, "10.0.0.1"); // 应该匹配/8
        verifyLookupConsistency(trie, fpa, "10.1.0.1"); // 应该匹配/16
        verifyLookupConsistency(trie, fpa, "10.1.2.1"); // 应该匹配/24
        verifyLookupConsistency(trie, fpa, "10.2.3.4"); // 应该匹配/8
    }

    @Test
    public void testLayerDepth() {
        // 添加需要深度遍历的前缀
        addPrefix("10.0.0.0", 8, new String[]{"root", "AS0"});
        addPrefix("10.0.1.0", 24, new String[]{"specific", "AS1"});
        addPrefix("10.0.2.0", 24, new String[]{"specific", "AS2"});
        addPrefix("10.0.3.0", 24, new String[]{"specific", "AS3"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        verifyLookupConsistency(trie, fpa, "10.0.1.1");
        verifyLookupConsistency(trie, fpa, "10.0.2.1");
        verifyLookupConsistency(trie, fpa, "10.0.3.1");
    }

    // ==================== 稀疏vs稠密分支测试 ====================

    @Test
    public void testSparseBranch() {
        // 创建稀疏分支：只有一个特殊值，其他都是默认
        addPrefix("10.0.0.0", 16, new String[]{"default", "AS0"});
        addPrefix("10.0.1.0", 24, new String[]{"special", "AS1"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        assertNotNull("FPA should not be null", fpa);
        verifyLookupConsistency(trie, fpa, "10.0.0.1");
        verifyLookupConsistency(trie, fpa, "10.0.1.1");
        verifyLookupConsistency(trie, fpa, "10.0.255.1");
    }

    @Test
    public void testDenseBranch() {
        // 创建稠密分支：许多不同的值
        for (int i = 0; i < 256; i++) {
            addPrefix("10.1." + i + ".0", 24, new String[]{"net-" + i, "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        assertNotNull("FPA should not be null", fpa);

        // 验证一些查找
        verifyLookupConsistency(trie, fpa, "10.1.0.1");
        verifyLookupConsistency(trie, fpa, "10.1.127.100");
        verifyLookupConsistency(trie, fpa, "10.1.255.255");
    }

    // ==================== 边界情况测试 ====================

    @Test
    public void testSlashZeroPrefix() {
        addPrefix("0.0.0.0", 0, new String[]{"default", "AS0"});
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        // 所有IP都应该匹配/0
        verifyLookupConsistency(trie, fpa, "1.2.3.4");
        verifyLookupConsistency(trie, fpa, "255.255.255.255");
    }

    @Test
    public void testSlashThirtyTwoPrefix() {
        addPrefix("1.2.3.4", 32, new String[]{"host", "AS1"});
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        verifyLookupConsistency(trie, fpa, "1.2.3.4");
        verifyLookupConsistency(trie, fpa, "1.2.3.5");
    }

    @Test
    public void testOverlappingPrefixes() {
        // 测试部分重叠的前缀
        addPrefix("192.168.1.0", 24, new String[]{"net1", "AS1"});
        addPrefix("192.168.2.0", 24, new String[]{"net2", "AS2"});
        addPrefix("192.168.0.0", 16, new String[]{"default", "AS0"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        verifyLookupConsistency(trie, fpa, "192.168.1.100");
        verifyLookupConsistency(trie, fpa, "192.168.2.100");
        verifyLookupConsistency(trie, fpa, "192.168.3.1");
    }

    // ==================== 复杂场景测试 ====================

    @Test
    public void testComplexRouteTable() {
        // 模拟真实路由表
        addPrefix("0.0.0.0", 0, new String[]{"default-route", "AS0"});
        addPrefix("10.0.0.0", 8, new String[]{"private-10", "AS10"});
        addPrefix("10.1.0.0", 16, new String[]{"vpn-10.1", "AS101"});
        addPrefix("172.16.0.0", 12, new String[]{"private-172", "AS172"});
        addPrefix("192.168.0.0", 16, new String[]{"private-192", "AS192"});
        addPrefix("8.8.8.0", 24, new String[]{"google-dns", "AS8"});
        addPrefix("1.1.1.0", 24, new String[]{"cloudflare-dns", "AS1"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        verifyLookupConsistency(trie, fpa, "10.1.2.3");
        verifyLookupConsistency(trie, fpa, "172.16.1.1");
        verifyLookupConsistency(trie, fpa, "192.168.1.1");
        verifyLookupConsistency(trie, fpa, "8.8.8.8");
        verifyLookupConsistency(trie, fpa, "1.1.1.1");
        verifyLookupConsistency(trie, fpa, "9.9.9.9");
    }

    @Test
    public void testLargePrefixSet() {
        // 添加1000个前缀
        for (int i = 0; i < 1000; i++) {
            int a = i & 0xFF;
            addPrefix(a + ".0.0.0", 8, new String[]{a + ".0.0.0/8", "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        // 验证一些查找
        verifyLookupConsistency(trie, fpa, "1.2.3.4");
        verifyLookupConsistency(trie, fpa, "100.200.100.50");
        verifyLookupConsistency(trie, fpa, "255.255.255.255");
    }

    // ==================== 一致性验证测试 ====================

    @Test
    public void testRandomPrefixesConsistency() {
        // 添加100个随机前缀
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int prefixLen = 8 + random.nextInt(17);
            String prefix = a + "." + b + ".0.0";
            addPrefix(prefix, prefixLen, new String[]{prefix + "/" + prefixLen, "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);

        // 验证1000个随机IP的查找一致性
        for (int i = 0; i < 1000; i++) {
            String ip = random.nextInt(256) + "." + random.nextInt(256) + "." +
                    random.nextInt(256) + "." + random.nextInt(256);
            verifyLookupConsistency(trie, fpa, ip);
        }
    }

    // ==================== 辅助验证方法 ====================

    /**
     * 验证Trie和FPA对给定IP的查找结果是否一致
     * 通过构建FPATree来验证，而不是直接操作FPA
     */
    private void verifyLookupConsistency(BitTrie<String[]> trie, ForwardingPortArray<String[]> fpa, String ipStr) {
        byte[] ipBytes = ipToBytes(ipStr);
        String[] trieResult = trie.get(ipBytes);

        // 构建FPATree进行验证
        FPATree<String[]> tree = FPATree.build(fpa);
        String[] treeResult = tree.lookup(ipBytes);

        if (trieResult == null) {
            assertNull("IP " + ipStr + " should return null", treeResult);
        } else {
            assertNotNull("IP " + ipStr + " should not return null", treeResult);
            assertArrayEquals("IP " + ipStr + " mismatch", trieResult, treeResult);
        }
    }
}
