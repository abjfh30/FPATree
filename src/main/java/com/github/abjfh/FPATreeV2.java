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
    // TYPE_SPARSE 暂不实现

    int K = 3;
    int[][] rootChunk = new int[LAYER1_GROUP_COUNT][GROUP_SIZE];
    List<ChunkEntry[]> chunkList = new ArrayList<>();
    List<V> resultList = new ArrayList<>();
    // Index Table: 256条目，记录每种8位组合中1的个数
    static final byte[] INDEX_TABLE = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            INDEX_TABLE[i] = (byte) Integer.bitCount(i);
        }
    }

    static class ChunkEntry {
        short[] codeWord;
        int[] lookupEntries;
        int defaultValue; // 整个 group 共享的默认值
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

        for (int j = 0; j < 4; j++) {
            short[] codeWords = new short[8];
            List<Integer> lookupEntries = new LinkedList<>();

            // 确定整个 group 的默认值（第一个元素）
            ForwardingPortArray.FPANode<V> firstNode = fpa.table.get(j * 64);
            int defaultValue;
            if (firstNode.next == null) {
                defaultValue =
                        encodeLookupEntry(
                                TYPE_LEAF, getValueIndex(firstNode.value, idxTable, tree));
            } else {
                int chunkIdx = processLayer(firstNode.next, idxTable, tree);
                defaultValue = encodeLookupEntry(TYPE_DENSE, chunkIdx);
            }

            int cumulativeOnes = 0; // 累积的 cluster 中 bitset 1 的个数

            for (int cluster = 0; cluster < 8; cluster++) {
                // 收集 cluster 的非默认值，确定 bitset
                List<Integer> clusterEntries = new ArrayList<>();
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
                            int chunkIdx = processLayer(node.next, idxTable, tree);
                            entry = encodeLookupEntry(TYPE_DENSE, chunkIdx);
                        }
                        clusterEntries.add(entry);
                    }
                }

                // 设置 before = cumulativeOnes
                codeWords[cluster] = (short) ((bitset << 8) | cumulativeOnes);

                // 添加非默认值到 lookupEntries
                lookupEntries.addAll(clusterEntries);

                cumulativeOnes += Integer.bitCount(bitset);
            }

            ChunkEntry entry = new ChunkEntry();
            entry.codeWord = codeWords;
            entry.lookupEntries = lookupEntries.stream().mapToInt(Integer::intValue).toArray();
            entry.defaultValue = defaultValue;
            group[j] = entry;
        }

        int chunkIdx = tree.chunkList.size();
        tree.chunkList.add(group);
        return chunkIdx;
    }

    public static <V> FPATreeV2<V> build(ForwardingPortArray<V> fpa) {

        Hashtable<V, Integer> idxTable = new Hashtable<>();
        FPATreeV2<V> tree = new FPATreeV2<>();

        // 确保索引 0 保留给 null 值（与 tmp4 一致）
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
                // [情况2] 有子树：构建 Layer 2 chunk (非最后一层)
                int chunkIdx = processLayer(node.next, idxTable, tree);
                tree.rootChunk[group1][bit1] = encodeLookupEntry(TYPE_DENSE, chunkIdx);
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
        if (ipBytes == null || ipBytes.length < 4) {
            return null;
        }

        V lastFoundValue = null;

        // Layer 1: 提取前 16 位
        int index16 = ((ipBytes[0] & 0xFF) << 8) | (ipBytes[1] & 0xFF);
        int group1 = index16 >> 6;
        int bit1 = index16 & 0b111111;

        int entry = rootChunk[group1][bit1];
        if (entry != 0) {
            int type = entry >>> 30;
            int index = entry & 0x3FFFFFFF;

            if (type == TYPE_LEAF) {
                lastFoundValue = resultList.get(index);
            } else if (type == TYPE_DENSE) {
                V value = searchInChunk(index, ipBytes, 2, lastFoundValue);
                if (value != null) {
                    lastFoundValue = value;
                }
            }
        }

        return lastFoundValue;
    }

    /**
     * 在 Chunk 中查询（Layer 2 或 Layer 3），支持最长前缀匹配
     *
     * @param chunkIdx chunkList 中的索引
     * @param ipBytes IP 地址字节数组
     * @param byteIdx 当前使用的字节索引 (2 或 3)
     * @param lastFoundValue 当前已找到的最长匹配值
     * @return 查找到的值
     */
    private V searchInChunk(int chunkIdx, byte[] ipBytes, int byteIdx, V lastFoundValue) {
        ChunkEntry[] group = chunkList.get(chunkIdx);

        if (byteIdx >= ipBytes.length) {
            return lastFoundValue;
        }

        int index8 = ipBytes[byteIdx] & 0xFF;
        int groupIdx = index8 >> 6; // 0-3
        int bitIdx = index8 & 0b111111; // 0-63

        ChunkEntry entry = group[groupIdx];

        int cluster = bitIdx >> 3;
        int bit = bitIdx & 0b111;

        short codeWord = entry.codeWord[cluster];
        int bitset = (codeWord >> 8) & 0xFF;
        int before = codeWord & 0xFF;

        // 优化：整个 cluster 都是默认值
        if (bitset == 0) {
            return resolveLookupEntry(entry.defaultValue, ipBytes, byteIdx + 1, lastFoundValue);
        }

        // 检查对应位是否为 1
        int mask = 1 << (7 - bit);
        if ((bitset & mask) == 0) {
            // 位为0，使用默认值
            return resolveLookupEntry(entry.defaultValue, ipBytes, byteIdx + 1, lastFoundValue);
        }

        // 位为1，从lookupEntries中获取
        // 计算簇内该位置之前的1的个数
        int shifted = bitset >> (8 - bit);
        int onesInCluster = INDEX_TABLE[shifted & 0xFF];
        int lookupIdx = before + onesInCluster;

        if (lookupIdx >= entry.lookupEntries.length) {
            return lastFoundValue;
        }

        return resolveLookupEntry(
                entry.lookupEntries[lookupIdx], ipBytes, byteIdx + 1, lastFoundValue);
    }

    /**
     * 解析 lookupEntry，支持最长前缀匹配
     *
     * @param lookupEntry 编码的入口
     * @param ipBytes IP 地址字节数组（用于 Layer 3 查询）
     * @param byteIdx 下一个字节索引
     * @param lastFoundValue 当前已找到的最长匹配值
     * @return 最终值
     */
    private V resolveLookupEntry(int lookupEntry, byte[] ipBytes, int byteIdx, V lastFoundValue) {
        if (lookupEntry == 0) {
            return lastFoundValue;
        }
        int type = lookupEntry >>> 30;
        int index = lookupEntry & 0x3FFFFFFF;

        if (type == TYPE_LEAF) {
            V value = resultList.get(index);
            // 只有当值非 null 时才更新，否则保留 lastFoundValue
            return (value != null) ? value : lastFoundValue;
        } else if (type == TYPE_DENSE) {
            // 继续查询 Layer 3
            V value = searchInChunk(index, ipBytes, byteIdx, lastFoundValue);
            return value;
        }
        return lastFoundValue;
    }
}
