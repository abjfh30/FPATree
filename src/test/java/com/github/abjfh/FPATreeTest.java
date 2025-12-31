package com.github.abjfh;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * FPATree单元测试
 *
 * <p>测试内容：
 * <ul>
 *   <li>基本查找功能
 *   <li>最长前缀匹配
 *   <li>边界情况
 *   <li>压缩结构
 *   <li>稀疏结构
 * </ul>
 */
public class FPATreeTest {

    private BitTrie<String[]> referenceTrie;
    private FPATree<String[]> fpaTree;

    @Before
    public void setUp() {
        referenceTrie = new BitTrie<>();
        fpaTree = null;
    }

    @After
    public void tearDown() {
        referenceTrie = null;
        fpaTree = null;
    }

    // ==================== 辅助方法 ====================

    /** 将IPv4字符串转换为字节数组 */
    private byte[] ipToBytes(String ipStr) {
        String[] parts = ipStr.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    /** 将IPv4字符串转换为int（大端序） */
    private int ipToInt(String ipStr) {
        String[] parts = ipStr.split("\\.");
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) | Integer.parseInt(parts[i]);
        }
        return result;
    }

    /** 添加前缀到Trie */
    private void addPrefix(String ipPrefix, int prefixLen, String[] value) {
        byte[] bytes = ipToBytes(ipPrefix);
        referenceTrie.put(bytes, prefixLen, value);
    }

    /** 构建FPATree */
    private void buildTree() {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(referenceTrie);
        fpaTree = FPATree.build(fpa);
    }

    /** 验证查找结果一致性 */
    private void assertLookupMatch(String ipStr) {
        byte[] ipBytes = ipToBytes(ipStr);
        String[] trieResult = referenceTrie.get(ipBytes);
        String[] treeResult = fpaTree.lookup(ipBytes);

        if (trieResult == null) {
            assertNull("Expected null for " + ipStr, treeResult);
        } else {
            assertNotNull("Expected non-null for " + ipStr, treeResult);
            assertArrayEquals("Mismatch for " + ipStr, trieResult, treeResult);
        }
    }

    // ==================== 基本功能测试 ====================

    @Test
    public void testEmptyTree() {
        buildTree();
        assertNull("Empty tree should return null", fpaTree.lookup(ipToBytes("1.2.3.4")));
    }

    @Test
    public void testSinglePrefix() {
        String[] value = {"1.0.0.0/8", "AS1"};
        addPrefix("1.0.0.0", 8, value);
        buildTree();

        // 测试匹配
        assertArrayEquals(value, fpaTree.lookup(ipToBytes("1.2.3.4")));
        assertArrayEquals(value, fpaTree.lookup(ipToBytes("1.255.255.255")));

        // 测试不匹配
        assertNull(fpaTree.lookup(ipToBytes("2.1.1.1")));
    }

    @Test
    public void testMultiplePrefixes() {
        addPrefix("10.0.0.0", 8, new String[]{"10.0.0.0/8", "AS10"});
        addPrefix("192.168.0.0", 16, new String[]{"192.168.0.0/16", "AS192"});
        addPrefix("8.8.8.0", 24, new String[]{"8.8.8.0/24", "AS8"});
        buildTree();

        assertLookupMatch("10.1.2.3");
        assertLookupMatch("192.168.1.1");
        assertLookupMatch("8.8.8.1");
        assertLookupMatch("1.2.3.4"); // 不存在
    }

    // ==================== 最长前缀匹配测试 ====================

    @Test
    public void testLongestPrefixMatch() {
        // 添加嵌套前缀
        addPrefix("10.0.0.0", 8, new String[]{"10.0.0.0/8", "broad"});
        addPrefix("10.1.0.0", 16, new String[]{"10.1.0.0/16", "narrow"});
        addPrefix("10.1.2.0", 24, new String[]{"10.1.2.0/24", "narrower"});
        buildTree();

        // 验证最长前缀匹配
        assertArrayEquals(new String[]{"10.1.2.0/24", "narrower"}, fpaTree.lookup(ipToBytes("10.1.2.100")));
        assertArrayEquals(new String[]{"10.1.2.0/24", "narrower"}, fpaTree.lookup(ipToBytes("10.1.2.255")));
        assertArrayEquals(new String[]{"10.1.0.0/16", "narrow"}, fpaTree.lookup(ipToBytes("10.1.3.1")));
        assertArrayEquals(new String[]{"10.0.0.0/8", "broad"}, fpaTree.lookup(ipToBytes("10.2.3.4")));
    }

    @Test
    public void testOverlappingPrefixes() {
        // 测试部分重叠的前缀
        addPrefix("192.168.1.0", 24, new String[]{"192.168.1.0/24", "net1"});
        addPrefix("192.168.2.0", 24, new String[]{"192.168.2.0/24", "net2"});
        addPrefix("192.168.0.0", 16, new String[]{"192.168.0.0/16", "default"});
        buildTree();

        assertArrayEquals(new String[]{"192.168.1.0/24", "net1"}, fpaTree.lookup(ipToBytes("192.168.1.100")));
        assertArrayEquals(new String[]{"192.168.2.0/24", "net2"}, fpaTree.lookup(ipToBytes("192.168.2.100")));
        assertArrayEquals(new String[]{"192.168.0.0/16", "default"}, fpaTree.lookup(ipToBytes("192.168.3.1")));
    }

    // ==================== 边界情况测试 ====================

    @Test
    public void testMinimumPrefix() {
        // /0 前缀（匹配所有）
        addPrefix("0.0.0.0", 0, new String[]{"0.0.0.0/0", "default"});
        buildTree();

        assertArrayEquals(new String[]{"0.0.0.0/0", "default"}, fpaTree.lookup(ipToBytes("1.2.3.4")));
        assertArrayEquals(new String[]{"0.0.0.0/0", "default"}, fpaTree.lookup(ipToBytes("255.255.255.255")));
    }

    @Test
    public void testMaximumPrefix() {
        // /32 前缀（精确匹配单个IP）
        addPrefix("1.2.3.4", 32, new String[]{"1.2.3.4/32", "host"});
        buildTree();

        assertArrayEquals(new String[]{"1.2.3.4/32", "host"}, fpaTree.lookup(ipToBytes("1.2.3.4")));
        assertNull(fpaTree.lookup(ipToBytes("1.2.3.5")));
    }

    @Test
    public void testNullInput() {
        buildTree();
        assertNull(fpaTree.lookup((byte[]) null));
        assertNull(fpaTree.lookup(new byte[0]));
    }

    @Test
    public void testBoundaryIPs() {
        addPrefix("0.0.0.0", 0, new String[]{"0.0.0.0/0", "all"});
        addPrefix("255.255.255.255", 32, new String[]{"255.255.255.255/32", "broadcast"});
        buildTree();

        assertLookupMatch("0.0.0.0");
        assertLookupMatch("255.255.255.255");
    }

    // ==================== 压缩结构测试 ====================

    @Test
    public void testDenseLayer() {
        // 创建密集的Layer 2/3结构（256个条目）
        for (int i = 0; i < 256; i++) {
            String prefix = "10.1." + i + ".0";
            addPrefix(prefix, 24, new String[]{prefix + "/24", "AS" + i});
        }
        buildTree();

        // 验证压缩chunk被创建
        assertFalse("Should have compressed chunks", fpaTree.compressedChunkList.isEmpty());

        // 验证查找正确性
        assertLookupMatch("10.1.0.1");
        assertLookupMatch("10.1.127.100");
        assertLookupMatch("10.1.255.255");
    }

    @Test
    public void testSparseLayer() {
        // 创建稀疏的Layer结构（只有少量非默认值）
        addPrefix("10.0.0.0", 16, new String[]{"10.0.0.0/16", "default"});
        addPrefix("10.0.1.0", 24, new String[]{"10.0.1.0/24", "specific1"});
        addPrefix("10.0.2.0", 24, new String[]{"10.0.2.0/24", "specific2"});
        buildTree();

        // 验证稀疏chunk被创建
        assertFalse("Should have sparse chunks", fpaTree.sparseChunkList.isEmpty());

        // 验证查找正确性
        assertLookupMatch("10.0.0.1");
        assertLookupMatch("10.0.1.1");
        assertLookupMatch("10.0.2.1");
        assertLookupMatch("10.0.3.1");
    }

    // ==================== 随机测试 ====================

    @Test
    public void testRandomPrefixes() {
        // 添加100个随机前缀
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int prefixLen = 8 + random.nextInt(17); // 8-24
            String prefix = a + "." + b + ".0.0";
            addPrefix(prefix, prefixLen, new String[]{prefix + "/" + prefixLen, "AS" + i});
        }
        buildTree();

        // 验证1000个随机IP
        for (int i = 0; i < 1000; i++) {
            String ip = random.nextInt(256) + "." + random.nextInt(256) + "." +
                    random.nextInt(256) + "." + random.nextInt(256);
            assertLookupMatch(ip);
        }
    }

    // ==================== 性能相关测试 ====================

    @Test
    public void testLargePrefixSet() {
        // 添加不同层级的前缀以测试Layer 2/3结构
        for (int i = 0; i < 256; i++) {
            // 添加/8前缀
            addPrefix(i + ".0.0.0", 8, new String[]{i + ".0.0.0/8", "AS" + i});

            // 添加/24前缀（触发Layer 2/3）
            addPrefix(i + ".0.0.0", 24, new String[]{i + ".0.0.0/24", "AS" + i + "-0"});
            addPrefix(i + ".1.0.0", 24, new String[]{i + ".1.0.0/24", "AS" + i + "-1"});
        }
        buildTree();

        // 验证结构大小合理
        assertTrue("Should have some compressed or sparse chunks",
                !fpaTree.compressedChunkList.isEmpty() || !fpaTree.sparseChunkList.isEmpty());

        // 验证一些查找
        assertLookupMatch("1.2.3.4");
        assertLookupMatch("100.200.100.50");
    }

    // ==================== int版本查找测试 ====================

    @Test
    public void testLookupWithInt() {
        addPrefix("10.0.0.0", 8, new String[]{"10.0.0.0/8", "AS10"});
        addPrefix("192.168.0.0", 16, new String[]{"192.168.0.0/16", "AS192"});
        buildTree();

        // 测试int版本查找
        String[] result1 = fpaTree.lookup(ipToInt("10.1.2.3"));
        assertArrayEquals(new String[]{"10.0.0.0/8", "AS10"}, result1);

        String[] result2 = fpaTree.lookup(ipToInt("192.168.1.1"));
        assertArrayEquals(new String[]{"192.168.0.0/16", "AS192"}, result2);

        String[] result3 = fpaTree.lookup(ipToInt("8.8.8.8"));
        assertNull(result3);
    }

    // ==================== 结果列表去重测试 ====================

    @Test
    public void testResultDeduplication() {
        // 添加相同值到多个前缀
        String[] sharedValue = {"shared", "AS0"};
        addPrefix("10.0.0.0", 8, sharedValue);
        addPrefix("192.168.0.0", 16, sharedValue);
        addPrefix("172.16.0.0", 12, sharedValue);
        buildTree();

        // 验证值共享（resultList应该去重）
        assertTrue("Result list should be small due to deduplication",
                fpaTree.resultList.size() <= 2); // 1 for shared + 1 for null

        // 验证查找正确
        assertArrayEquals(sharedValue, fpaTree.lookup(ipToBytes("10.1.2.3")));
        assertArrayEquals(sharedValue, fpaTree.lookup(ipToBytes("192.168.1.1")));
        assertArrayEquals(sharedValue, fpaTree.lookup(ipToBytes("172.16.1.1")));
    }

    // ==================== 不同K值测试 ====================

    @Test
    public void testDifferentKValues() {
        // 创建适合稀疏优化的结构
        addPrefix("10.0.0.0", 16, new String[]{"10.0.0.0/16", "default"});
        addPrefix("10.0.1.0", 24, new String[]{"10.0.1.0/24", "specific"});

        // 使用不同的K值构建
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(referenceTrie);

        FPATree<String[]> treeK1 = FPATree.build(fpa, 1);
        FPATree<String[]> treeK5 = FPATree.build(fpa, 5);

        byte[] testIp = ipToBytes("10.0.1.1");
        String[] expected = new String[]{"10.0.1.0/24", "specific"};

        assertArrayEquals(expected, treeK1.lookup(testIp));
        assertArrayEquals(expected, treeK5.lookup(testIp));
    }
}
