package com.github.abjfh;

/** 测试 BitTrie 的 compress 方法 */
public class BitTrieCompressTest {

    public static void main(String[] args) {
        testCompressWithSameChildren();
        testCompressWithDifferentValues();
        testCompressSingleNode();
        testCompressEmptyTree();
        testCompressFullBinarySubtree();

        System.out.println("\n=== 所有测试通过! ===");
    }

    public static void testCompressWithSameChildren() {
        System.out.println("\n=== 测试: compressWithSameChildren ===");
        BitTrie<String> trie = new BitTrie<>();

        String value = "V1";

        byte[] key1 = new byte[] {(byte) 192, (byte) 168, 0, 0};
        trie.put(key1, 16, value);

        byte[] key2 = new byte[] {(byte) 192, (byte) 168, (byte) 255, 0};
        trie.put(key2, 24, value);

        assertEqual("V1", trie.get(key1), "查询 key1 应该返回 V1");
        assertEqual("V1", trie.get(key2), "查询 key2 应该返回 V1");

        System.out.println("压缩前的树结构:");
        printTree(trie.root, 0);

        trie.compress();

        System.out.println("\n压缩后的树结构:");
        printTree(trie.root, 0);

        assertEqual("V1", trie.get(key1), "压缩后查询 key1 应该返回 V1");
        assertEqual("V1", trie.get(key2), "压缩后查询 key2 应该返回 V1");

        System.out.println("✓ 测试通过");
    }

    public static void testCompressWithDifferentValues() {
        System.out.println("\n=== 测试: compressWithDifferentValues ===");
        BitTrie<String> trie = new BitTrie<>();

        String value1 = "V1";
        String value2 = "V2";

        byte[] key1 = new byte[] {(byte) 192, (byte) 168, 0, 0};
        trie.put(key1, 16, value1);

        byte[] key2 = new byte[] {(byte) 192, (byte) 168, (byte) 255, 0};
        trie.put(key2, 24, value2);

        trie.compress();

        assertEqual(value1, trie.get(key1), "压缩后查询 key1 应该返回 V1");
        assertEqual(value2, trie.get(key2), "压缩后查询 key2 应该返回 V2");

        System.out.println("✓ 测试通过");
    }

    public static void testCompressSingleNode() {
        System.out.println("\n=== 测试: compressSingleNode ===");
        BitTrie<String> trie = new BitTrie<>();

        byte[] key = new byte[] {(byte) 192, 0, 0, 0};
        trie.put(key, 8, "V1");

        trie.compress();

        assertEqual("V1", trie.get(key), "压缩后查询应该返回 V1");

        System.out.println("✓ 测试通过");
    }

    public static void testCompressEmptyTree() {
        System.out.println("\n=== 测试: compressEmptyTree ===");
        BitTrie<String> trie = new BitTrie<>();

        trie.compress();

        byte[] key = new byte[] {(byte) 192, 0, 0, 0};
        assertNull(trie.get(key), "空树查询应该返回 null");

        System.out.println("✓ 测试通过");
    }

    public static void testCompressFullBinarySubtree() {
        System.out.println("\n=== 测试: testCompressFullBinarySubtree ===");
        BitTrie<String> trie = new BitTrie<>();

        String value = "V1";

        // 插入两个前缀，它们在某一位上不同（0和1），但值相同
        byte[] key1 = new byte[] {0, 0, 0, 0};
        byte[] key2 = new byte[] {0, 0, (byte) 128, 0}; // 第17位是1

        trie.put(key1, 16, value); // 前16位
        trie.put(key2, 17, value); // 前17位

        System.out.println("压缩前的树结构:");
        printTree(trie.root, 0);

        trie.compress();

        System.out.println("\n压缩后的树结构:");
        printTree(trie.root, 0);

        assertEqual("V1", trie.get(key1), "压缩后查询 key1 应该返回 V1");
        assertEqual("V1", trie.get(key2), "压缩后查询 key2 应该返回 V1");

        System.out.println("✓ 测试通过");
    }

    private static void printTree(BitTrie.TrieNode<?> node, int depth) {
        if (node == null) {
            return;
        }

        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }

        System.out.println(
                indent + "Node: " + (node.isLeaf ? "LEAF(" + node.value + ")" : "INTERNAL"));

        if (node.leftChild != null || node.rightChild != null) {
            System.out.println(indent + "  Left:");
            printTree(node.leftChild, depth + 2);
            System.out.println(indent + "  Right:");
            printTree(node.rightChild, depth + 2);
        }
    }

    private static void assertEqual(String expected, String actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

    private static void assertNull(Object actual, String message) {
        if (actual != null) {
            throw new AssertionError(message + " (expected: null, actual: " + actual + ")");
        }
    }
}
