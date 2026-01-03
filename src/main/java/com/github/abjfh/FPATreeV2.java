package com.github.abjfh;

import java.util.*;

public class FPATreeV2<V> {
    // lookupEntry 用 int 表示
    // 前 2 位表示类型 ：0 值索引、 1 非稀疏 GroupList 索引、 2 稀疏 GroupList 索引
    // 稀疏结构
    private static final int LAYER1_GROUP_COUNT = 1024; // Layer 1 组数
    private static final int LAYER23_GROUP_COUNT = 4; // Layer 2/3 组数
    private static final int GROUP_SIZE = 64; // 每组元素数量

    // lookupEntry 类型常量
    private static final int TYPE_LEAF = 0; // 00: 值索引 (指向 resultList)
    private static final int TYPE_DENSE = 1; // 01: 非稀疏 chunk 索引 (指向 chunkList)
    private static final int TYPE_SPARSE = 2; // 02: 稀疏 chunk 索引 (指向 sparseChunkList)

    int K = 8;
    int[][] rootChunk = new int[LAYER1_GROUP_COUNT][GROUP_SIZE];
    List<ChunkEntry[]> chunkList = new ArrayList<>();
    List<ChunkEntry> sparseChunkList = new ArrayList<>();
    List<V> resultList = new ArrayList<>();
    // Index Table: 256条目，记录每种8位组合中1的个数
    static final byte[] INDEX_TABLE = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            INDEX_TABLE[i] = (byte) Integer.bitCount(i);
        }
    }

    static class ChunkEntry {
        short[] codeWords;
        int[] lookupEntries;
    }

    static class SparseChunkEntry implements Comparable<SparseChunkEntry> {
        short codeWord;
        int lookupEntry;

        @Override
        public int compareTo(SparseChunkEntry o) {
            // 先按前缀长度降序排序（最长前缀优先）
            int prefixLen1 = this.codeWord & 0xFF;
            int prefixLen2 = o.codeWord & 0xFF;
            if (prefixLen1 != prefixLen2) {
                return prefixLen2 - prefixLen1; // 降序
            }
            // 前缀长度相同时，按mask升序排序
            int mask1 = (this.codeWord >> 8) & 0xFF;
            int mask2 = (o.codeWord >> 8) & 0xFF;
            return mask1 - mask2;
        }
    }

    /**
     * 编码 lookupEntry
     *
     * @param type 类型 (TYPE_LEAF, TYPE_DENSE)
     * @param index 索引值
     * @return 编码后的 32 位整数
     */
    private static int encodeLookupEntry(int type, int index) {
        return (type << 30) | (index & 0x3FFFFFFF);
    }

    /**
     * 获取值的索引（去重）
     *
     * @param value 要存储的值
     * @param idxTable 值到索引的映射表
     * @param tree FPATreeV2 实例
     * @return 值在 resultList 中的索引
     */
    private static <V> int getValueIndex(
            V value, Hashtable<V, Integer> idxTable, FPATreeV2<V> tree) {
        if (value == null) {
            return 0; // null 值索引为 0
        }
        return idxTable.compute(
                value,
                (v, existingIdx) -> {
                    if (existingIdx == null) {
                        tree.resultList.add(v);
                        return tree.resultList.size() - 1;
                    }
                    return existingIdx;
                });
    }

    /**
     * 处理通用层 (8位)，构建 ChunkEntry[4]
     *
     * @param fpa 当前层的 ForwardingPortArray (大小256)
     * @param idxTable 值索引表
     * @param tree FPATreeV2 实例
     * @return chunkList 中的索引
     */
    private static <V> int processLayer(
            ForwardingPortArray<V> fpa, Hashtable<V, Integer> idxTable, FPATreeV2<V> tree) {
        ChunkEntry[] group = new ChunkEntry[4];
        int totalEntries = 0;
        for (int j = 0; j < 4; j++) {
            short[] codeWords = new short[8];
            List<Integer> lookupEntries = new LinkedList<>();
            ForwardingPortArray.FPANode<V> firstNode = null;
            int before = 0;

            for (int cluster = 0; cluster < 8; cluster++) {
                int bitset = 0;

                for (int bit = 0; bit < 8; bit++) {
                    int k = cluster * 8 + bit;
                    ForwardingPortArray.FPANode<V> node = fpa.table.get(j * 64 + k);

                    if (!Objects.equals(firstNode, node)) {
                        bitset |= (1 << (7 - bit));
                        int entry;
                        if (node.next == null) {
                            entry =
                                    encodeLookupEntry(
                                            TYPE_LEAF, getValueIndex(node.value, idxTable, tree));
                        } else {
                            entry = processLayer(node.next, idxTable, tree);
                        }
                        firstNode = node;
                        lookupEntries.add(entry);
                    }
                }

                // 设置 before
                codeWords[cluster] = (short) ((bitset << 8) | before);
                before = lookupEntries.size();
            }

            ChunkEntry entry = new ChunkEntry();
            entry.codeWords = codeWords;
            entry.lookupEntries = lookupEntries.stream().mapToInt(Integer::intValue).toArray();
            totalEntries += entry.lookupEntries.length;
            group[j] = entry;
        }
        if (totalEntries > tree.K) {
            int chunkIdx = tree.chunkList.size();
            tree.chunkList.add(group);
            return encodeLookupEntry(TYPE_DENSE, chunkIdx);
        } else {
            return processLayer(fpa, tree, group);
        }
    }

    private static <V> int processLayer(
            ForwardingPortArray<V> fpa, FPATreeV2<V> tree, ChunkEntry[] group) {
        BitTrie<Integer> trie = new BitTrie<>();
        byte[] bytes = new byte[1];
        for (int i = 0; i < fpa.table.size(); i++) {
            int lookupEntry = searchLookupEntry(group, i);
            bytes[0] = (byte) i; // 把索引i作为key（0-255）
            trie.put(bytes, 8, lookupEntry); // lookupEntry作为value
        }
        trie.compress();
        LinkedList<SparseChunkEntry> sparseChunkEntries = new LinkedList<>();
        trie.preorderTraversalIterative(
                (prefix, v) -> {
                    SparseChunkEntry entry = new SparseChunkEntry();
                    for (int i = 0; i < prefix.size(); i++) {
                        if (prefix.get(i)) {
                            entry.codeWord |= (short) (1 << (7 - i + 8));
                        }
                    }
                    entry.codeWord |= (short) (prefix.size() & 0xFF);
                    entry.lookupEntry = v;
                    sparseChunkEntries.add(entry);
                });
        Collections.sort(sparseChunkEntries);
        ChunkEntry chunkEntry = new ChunkEntry();
        chunkEntry.lookupEntries = new int[sparseChunkEntries.size()];
        chunkEntry.codeWords = new short[sparseChunkEntries.size()];
        for (int i = 0; i < sparseChunkEntries.size(); i++) {
            SparseChunkEntry entry = sparseChunkEntries.get(i);
            chunkEntry.lookupEntries[i] = entry.lookupEntry;
            chunkEntry.codeWords[i] = entry.codeWord;
        }
        int entryIdx = tree.sparseChunkList.size();
        tree.sparseChunkList.add(chunkEntry);

        return encodeLookupEntry(TYPE_SPARSE, entryIdx);
    }

    public static <V> FPATreeV2<V> build(ForwardingPortArray<V> fpa) {

        Hashtable<V, Integer> idxTable = new Hashtable<>();
        FPATreeV2<V> tree = new FPATreeV2<>();

        // 确保索引 0 保留给 null 值
        tree.resultList.add(null);

        int size = fpa.table.size(); // Layer 1 大小: 2^16 = 65536

        for (int i = 0; i < size; i++) {
            // i 代表 Layer 1 的 16 位索引
            // 高 6 位: group1 (0-1023)
            // 低 6 位: bit1 (0-63)
            int group1 = i >> 6;
            int bit1 = i & 0b111111;

            ForwardingPortArray.FPANode<V> node = fpa.table.get(i);

            if (node.next == null) {
                // [情况1] 叶子节点：直接存储值索引
                int valueIdx = getValueIndex(node.value, idxTable, tree);
                tree.rootChunk[group1][bit1] = encodeLookupEntry(TYPE_LEAF, valueIdx);
            } else {
                // [情况2] 有子树
                tree.rootChunk[group1][bit1] = processLayer(node.next, idxTable, tree);
            }
        }

        return tree;
    }

    /**
     * 查询 IP 地址对应的值（最长前缀匹配）
     *
     * @param ipBytes IP 地址的字节数组 (IPv4: 4字节)
     * @return 查找到的值，未找到返回 null
     */
    public V search(byte[] ipBytes) {
        if (ipBytes == null || ipBytes.length != 4) {
            return null;
        }

        // Layer 1: 提取前 16 位
        int index16 = ((ipBytes[0] & 0xFF) << 8) | (ipBytes[1] & 0xFF);
        int group1 = index16 >> 6;
        int bit1 = index16 & 0b111111;

        int entry = rootChunk[group1][bit1];
        return resolveLookupEntry(entry, ipBytes, 2);
    }

    /**
     * 在 Chunk 中查询（Layer 2 或 Layer 3），支持最长前缀匹配
     *
     * @param chunkIdx chunkList 中的索引
     * @param ipBytes IP 地址字节数组
     * @param byteIdx 当前使用的字节索引 (2 或 3)
     * @return 查找到的值
     */
    private V searchInChunk(int chunkIdx, byte[] ipBytes, int byteIdx) {
        ChunkEntry[] group = chunkList.get(chunkIdx);
        int lookupEntry = searchLookupEntry(group, ipBytes[byteIdx] & 0xFF);
        return resolveLookupEntry(lookupEntry, ipBytes, byteIdx + 1);
    }

    private static int searchLookupEntry(ChunkEntry[] group, int index8) {

        int groupIdx = index8 >> 6; // 0-3
        int bitIdx = index8 & 0b111111; // 0-63

        ChunkEntry entry = group[groupIdx];

        int cluster = bitIdx >> 3;
        int bit = bitIdx & 0b111;

        short codeWord = entry.codeWords[cluster];
        int bitset = (codeWord >> 8) & 0xFF;
        int before = codeWord & 0xFF;
        // 位为1，从lookupEntries中获取
        // 计算簇内该位置之前的1的个数
        int shifted = bitset >> (8 - bit - 1);
        int onesInCluster = INDEX_TABLE[shifted];
        int lookupIdx = before + onesInCluster - 1;

        return entry.lookupEntries[lookupIdx];
    }

    /**
     * 解析 lookupEntry，支持最长前缀匹配
     *
     * @param lookupEntry 编码的入口
     * @param ipBytes IP 地址字节数组（用于 Layer 3 查询）
     * @param byteIdx 下一个字节索引
     * @return 最终值
     */
    private V resolveLookupEntry(int lookupEntry, byte[] ipBytes, int byteIdx) {

        int type = lookupEntry >>> 30;
        int index = lookupEntry & 0x3FFFFFFF;

        if (type == TYPE_LEAF) {
            return resultList.get(index);
        } else if (type == TYPE_DENSE) {
            // 继续查询 Layer 3
            return searchInChunk(index, ipBytes, byteIdx);
        } else if (type == TYPE_SPARSE) {
            return searchInSparseChunk(index, ipBytes, byteIdx);
        }
        return null;
    }

    private V searchInSparseChunk(int chunkIdx, byte[] ipBytes, int byteIdx) {
        ChunkEntry chunkEntry = sparseChunkList.get(chunkIdx);
        byte ipByte = ipBytes[byteIdx];
        for (int i = chunkEntry.codeWords.length - 1; i >= 0; i--) {
            short codeWord = chunkEntry.codeWords[i];
            // 提取前缀长度和前缀值
            int prefixLen = codeWord & 0xFF;
            int prefixVal = (codeWord >> 8) & 0xFF;
            // prefixLen=0 表示默认匹配所有
            if (prefixLen == 0) {
                return resolveLookupEntry(chunkEntry.lookupEntries[i], ipBytes, byteIdx + 1);
            }
            // 检查 ipByte 的高 prefixLen 位是否等于 prefixVal 的高 prefixLen 位
            int ipPrefix = (ipByte & 0xFF) >>> (8 - prefixLen);
            int valPrefix = prefixVal >>> (8 - prefixLen);
            if (ipPrefix == valPrefix) {
                return resolveLookupEntry(chunkEntry.lookupEntries[i], ipBytes, byteIdx + 1);
            }
        }
        return null;
    }

    /**
     * 统计存储使用情况。
     *
     * @return 存储统计信息
     */
    public MemoryStats getMemoryStats() {
        MemoryStats stats = new MemoryStats();

        // 1. rootChunk: int[1024][64]
        stats.rootChunkBytes = LAYER1_GROUP_COUNT * GROUP_SIZE * 4L;
        stats.rootChunkCount = LAYER1_GROUP_COUNT * GROUP_SIZE;

        // 2. chunkList 统计
        stats.chunkListCount = chunkList.size();
        for (ChunkEntry[] group : chunkList) {
            for (ChunkEntry entry : group) {
                // codeWords: short[8] = 16 字节
                stats.codeWordsBytes += 16;
                // lookupEntries: int[] 大小
                stats.lookupEntriesBytes += entry.lookupEntries.length * 4L;
                stats.lookupEntriesCount += entry.lookupEntries.length;
            }
        }

        // 3. sparseChunkList 统计
        stats.sparseChunkListCount = sparseChunkList.size();
        for (ChunkEntry entry : sparseChunkList) {
            // codeWords: short[] 大小
            stats.sparseCodeWordsBytes += entry.codeWords.length * 2L;
            // lookupEntries: int[] 大小
            stats.sparseLookupEntriesBytes += entry.lookupEntries.length * 4L;
            stats.sparseLookupEntriesCount += entry.lookupEntries.length;
        }

        // 4. resultList 统计
        stats.resultListCount = resultList.size();
        // 估算 String 存储：每个 String 对象约 40 字节 + 字符数组
        for (V value : resultList) {
            if (value != null) {
                stats.resultListBytes += estimateStringSize(value.toString());
            }
        }

        // 5. INDEX_TABLE
        stats.indexTableBytes = 256;

        return stats;
    }

    /**
     * 估算 String 的内存占用。
     *
     * @param str 字符串
     * @return 估算的字节数
     */
    private long estimateStringSize(String str) {
        // Java String 对象头约 16 字节 + hash 4 字节 + 引用 4 字节 ≈ 24 字节
        // char[] 数组头 16 字节 + 每个字符 2 字节
        return 24 + 16 + str.length() * 2L;
    }

    /** 打印内存统计信息。 */
    public void printMemoryStats() {
        MemoryStats stats = getMemoryStats();

        System.out.println("\n========================================");
        System.out.println("FPATreeV2 存储统计");
        System.out.println("========================================");

        System.out.println("\n【rootChunk】Layer 1 (int[1024][64]):");
        System.out.printf("  元素数量: %,d%n", stats.rootChunkCount);
        System.out.printf(
                "  存储大小: %,d bytes (%.2f KB)%n",
                stats.rootChunkBytes, stats.rootChunkBytes / 1024.0);

        System.out.println("\n【chunkList】Dense chunks (Layer 2/3):");
        System.out.printf("  chunk 数量: %,d%n", stats.chunkListCount);
        System.out.printf(
                "  codeWords: %,d bytes (%.2f KB)%n",
                stats.codeWordsBytes, stats.codeWordsBytes / 1024.0);
        System.out.printf(
                "  lookupEntries: %,d 个, %,d bytes (%.2f KB)%n",
                stats.lookupEntriesCount,
                stats.lookupEntriesBytes,
                stats.lookupEntriesBytes / 1024.0);
        long chunkListTotal = stats.codeWordsBytes + stats.lookupEntriesBytes;
        System.out.printf(
                "  chunkList 总计: %,d bytes (%.2f MB)%n",
                chunkListTotal, chunkListTotal / (1024.0 * 1024.0));

        System.out.println("\n【sparseChunkList】Sparse chunks (Layer 2/3):");
        System.out.printf("  chunk 数量: %,d%n", stats.sparseChunkListCount);
        System.out.printf(
                "  codeWords: %,d bytes (%.2f KB)%n",
                stats.sparseCodeWordsBytes, stats.sparseCodeWordsBytes / 1024.0);
        System.out.printf(
                "  lookupEntries: %,d 个, %,d bytes (%.2f KB)%n",
                stats.sparseLookupEntriesCount,
                stats.sparseLookupEntriesBytes,
                stats.sparseLookupEntriesBytes / 1024.0);
        long sparseChunkListTotal = stats.sparseCodeWordsBytes + stats.sparseLookupEntriesBytes;
        System.out.printf(
                "  sparseChunkList 总计: %,d bytes (%.2f MB)%n",
                sparseChunkListTotal, sparseChunkListTotal / (1024.0 * 1024.0));

        System.out.println("\n【resultList】值去重存储:");
        System.out.printf("  值数量: %,d%n", stats.resultListCount);
        System.out.printf(
                "  存储大小: %,d bytes (%.2f MB)%n",
                stats.resultListBytes, stats.resultListBytes / (1024.0 * 1024.0));

        System.out.println("\n【INDEX_TABLE】:");
        System.out.printf("  存储大小: %,d bytes%n", stats.indexTableBytes);

        // 总计（不含 resultList）
        long totalWithoutResult =
                stats.rootChunkBytes
                        + chunkListTotal
                        + sparseChunkListTotal
                        + stats.indexTableBytes;
        System.out.println("\n========================================");
        System.out.printf(
                "总计（不含 resultList）: %,d bytes (%.2f MB)%n",
                totalWithoutResult, totalWithoutResult / (1024.0 * 1024.0));
        System.out.printf(
                "总计（含 resultList）: %,d bytes (%.2f MB)%n",
                totalWithoutResult + stats.resultListBytes,
                (totalWithoutResult + stats.resultListBytes) / (1024.0 * 1024.0));
        System.out.println("========================================");
    }

    /** 存储统计信息。 */
    public static class MemoryStats {
        public long rootChunkBytes;
        public int rootChunkCount;

        public int chunkListCount;
        public long codeWordsBytes;
        public long lookupEntriesBytes;
        public int lookupEntriesCount;

        public int sparseChunkListCount;
        public long sparseCodeWordsBytes;
        public long sparseLookupEntriesBytes;
        public int sparseLookupEntriesCount;

        public int resultListCount;
        public long resultListBytes;

        public long indexTableBytes;
    }
}
