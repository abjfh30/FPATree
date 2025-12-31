package com.github.abjfh;

import org.junit.Test;

import java.util.Random;

/**
 * 性能验证测试 - 验证优化后的代码运行正常
 *
 * <p>此测试不测量绝对性能，而是验证：
 * 1. 大量查找操作能够正常完成
 * 2. 没有性能回归（通过逻辑正确性验证）
 */
public class PerformanceValidationTest {

    @Test
    public void testLargeScaleLookupPerformance() {
        BitTrie<String[]> trie = new BitTrie<>();

        // 创建10000个随机前缀
        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int c = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
            trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        // 执行100000次查找验证性能和正确性
        for (int i = 0; i < 100000; i++) {
            byte[] ip = new byte[]{
                (byte) random.nextInt(256),
                (byte) random.nextInt(256),
                (byte) random.nextInt(256),
                (byte) random.nextInt(256)
            };

            String[] trieResult = trie.get(ip);
            String[] treeResult = tree.lookup(ip);

            if (trieResult == null) {
                assert treeResult == null : "Expected null for IP " + bytesToIP(ip);
            } else {
                assert treeResult != null : "Expected non-null for IP " + bytesToIP(ip);
                assert trieResult.length == treeResult.length : "Result length mismatch";
                for (int j = 0; j < trieResult.length; j++) {
                    assert trieResult[j].equals(treeResult[j]) : "Result mismatch at index " + j;
                }
            }
        }
    }

    @Test
    public void testCompressedStructureLookupPerformance() {
        BitTrie<String[]> trie = new BitTrie<>();

        // 创建密集的Layer 2/3结构
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                byte[] prefixIp = new byte[]{(byte) (10 + a), (byte) b, 0, 0};
                trie.put(prefixIp, 24, new String[]{(10 + a) + "." + b + ".0.0/24", "AS" + (a * 16 + b)});
            }
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) {
            byte[] ip = new byte[]{
                (byte) (10 + random.nextInt(16)),
                (byte) random.nextInt(16),
                (byte) random.nextInt(256),
                (byte) random.nextInt(256)
            };

            String[] trieResult = trie.get(ip);
            String[] treeResult = tree.lookup(ip);

            // 验证结果一致（可能都是null，如果随机IP不在任何前缀范围内）
            if (trieResult == null) {
                assert treeResult == null : "Trie result is null but tree result is not";
            } else {
                assert treeResult != null : "Trie result is not null but tree result is null";
                assert trieResult[0].equals(treeResult[0]) : "Result mismatch";
            }
        }
    }

    @Test
    public void testSparseStructureLookupPerformance() {
        BitTrie<String[]> trie = new BitTrie<>();

        // 创建稀疏结构
        trie.put(new byte[]{(byte) 10, 0, 0, 0}, 16, new String[]{"10.0.0.0/16", "default"});
        trie.put(new byte[]{(byte) 10, 0, 1, 0}, 24, new String[]{"10.0.1.0/24", "specific"});
        trie.put(new byte[]{(byte) 10, 0, 2, 0}, 24, new String[]{"10.0.2.0/24", "specific"});

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) {
            byte[] ip = new byte[]{
                (byte) 10,
                (byte) 0,
                (byte) random.nextInt(256),
                (byte) random.nextInt(256)
            };

            String[] trieResult = trie.get(ip);
            String[] treeResult = tree.lookup(ip);

            assert trieResult != null : "Trie result should not be null";
            assert treeResult != null : "Tree result should not be null";
            assert trieResult[0].equals(treeResult[0]) : "Result mismatch for IP " + bytesToIP(ip);
        }
    }

    @Test
    public void testByteArrayToIntConversion() {
        // 测试新的位操作合并转换方式
        byte[] ip1 = {(byte) 192, (byte) 168, 1, 1};
        byte[] ip2 = {10, 0, 0, 1};
        byte[] ip3 = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};

        // 使用新的转换方式
        int int1 = ((ip1[0] & 0xFF) << 24) | ((ip1[1] & 0xFF) << 16) | ((ip1[2] & 0xFF) << 8) | (ip1[3] & 0xFF);
        int int2 = ((ip2[0] & 0xFF) << 24) | ((ip2[1] & 0xFF) << 16) | ((ip2[2] & 0xFF) << 8) | (ip2[3] & 0xFF);
        int int3 = ((ip3[0] & 0xFF) << 24) | ((ip3[1] & 0xFF) << 16) | ((ip3[2] & 0xFF) << 8) | (ip3[3] & 0xFF);

        // 验证转换正确性
        assert int1 == 0xC0A80101 : "192.168.1.1 conversion failed: " + Integer.toHexString(int1);
        assert int2 == 0x0A000001 : "10.0.0.1 conversion failed: " + Integer.toHexString(int2);
        assert int3 == 0xFFFFFFFF : "255.255.255.255 conversion failed: " + Integer.toHexString(int3);

        // 验证lookup方法能正确使用转换后的int
        BitTrie<String[]> trie = new BitTrie<>();
        trie.put(ip1, 32, new String[]{"192.168.1.1/32", "test"});
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        String[] result = tree.lookup(int1);
        assert result != null : "Lookup by int should return result";
        assert result[0].equals("192.168.1.1/32") : "Result mismatch";
    }

    private String bytesToIP(byte[] ip) {
        return (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
    }
}
