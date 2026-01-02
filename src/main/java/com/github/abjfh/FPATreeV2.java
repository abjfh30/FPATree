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
            entry.codeWord = codeWords;
            entry.lookupEntries = lookupEntries.stream().mapToInt(Integer::intValue).toArray();
            group[j] = entry;
        }

        int chunkIdx = tree.chunkList.size();
        tree.chunkList.add(group);
        return encodeLookupEntry(TYPE_DENSE, chunkIdx);
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

        int index8 = ipBytes[byteIdx] & 0xFF;
        int groupIdx = index8 >> 6; // 0-3
        int bitIdx = index8 & 0b111111; // 0-63

        ChunkEntry entry = group[groupIdx];

        int cluster = bitIdx >> 3;
        int bit = bitIdx & 0b111;

        short codeWord = entry.codeWord[cluster];
        int bitset = (codeWord >> 8) & 0xFF;
        int before = codeWord & 0xFF;
        // 位为1，从lookupEntries中获取
        // 计算簇内该位置之前的1的个数
        int shifted = bitset >> (8 - bit - 1);
        int onesInCluster = INDEX_TABLE[shifted];
        int lookupIdx = before + onesInCluster - 1;

        return resolveLookupEntry(entry.lookupEntries[lookupIdx], ipBytes, byteIdx + 1);
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
        }
        return null;
    }
}
