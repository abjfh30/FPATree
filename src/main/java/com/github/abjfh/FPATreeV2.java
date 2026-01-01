package com.github.abjfh;

import java.util.*;

public class FPATreeV2<V> {
    // lookupEntry 用 int 表示
    // 前 2 位表示类型 ：0 值索引、 1 非稀疏 GroupList 索引、 2 稀疏 GroupList 索引
    // 稀疏结构
    private static final int LAYER1_GROUP_COUNT = 1024; // Layer 1 组数
    private static final int LAYER23_GROUP_COUNT = 4; // Layer 2/3 组数
    private static final int GROUP_SIZE = 64; // 每组元素数量

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
        // bitset 8 | before 8 或 prefix 8 | mask 8
        short[] codeWord;
        int[] lookupEntries;
    }

    public static <V> FPATreeV2<V> build(ForwardingPortArray<V> fpa) {

        Hashtable<V, Integer> idxTable = new Hashtable<>();
        FPATreeV2<V> tree = new FPATreeV2<>();
        int size = fpa.table.size();
        for (int i = 0; i < size; i++) {
            // 此时i代表L1的16位int值
            // i前六位为 group1,后六位为 bit1
            int group1 = i >> 6;
            int bit1 = i & 0b111111;

            ForwardingPortArray.FPANode<V> node = fpa.table.get(i);
            // 如果有next 节点:
            if (node.next != null) {
                // lookupEntry 应该跳入L2
                // 这里先构建一个chunk节点
                ForwardingPortArray<V> fpa_l2 = node.next;
                ChunkEntry[] group2 = new ChunkEntry[4];
                for (int j = 0; j < 4; j++) {
                    short[] codeWords = new short[8];
                    List<Integer> lookupEntries = new LinkedList<>();
                    ForwardingPortArray.FPANode<V> firstNodeL2 = null;
                    for (int k = 0; k < 64; k++) {
                        int idx = j * 64 + k;
                        ForwardingPortArray.FPANode<V> nextNodeL2 = fpa_l2.table.get(idx);
                        if (!Objects.equals(firstNodeL2, nextNodeL2)) {
                            int cluster2 = (idx >> 3) & 0b111;
                            int bit2 = idx & 0b111;
                            codeWords[cluster2] ^= (short) (bit2 << 8);
                            // 同样的 判断是否跳入L3
                            if (nextNodeL2.next != null) {
                                ForwardingPortArray<V> fpa_l3 = nextNodeL2.next;
                                ChunkEntry[] group3 = new ChunkEntry[4];
                                // 填充对应的4个组
                                for (int j3 = 0; j3 < 4; j3++) {
                                    short[] codeWordsL3 = new short[8];
                                    List<Integer> lookupEntriesL3 = new LinkedList<>();
                                    ForwardingPortArray.FPANode<V> firstNodeL3 = null;
                                    int countL3 = 0;
                                    // 压缩每个组中的64个元素为位图
                                    for (int k3 = 0; k3 < 64; k3++) {
                                        int idxL3 = j3 * 64 + k3;
                                        int cluster3 = (idxL3 >> 3) & 0b111;
                                        int bit3 = idxL3 & 0b111;
                                        ForwardingPortArray.FPANode<V> nextNodeL3 =
                                                fpa_l3.table.get(idxL3);
                                        if (!Objects.equals(firstNodeL3, nextNodeL3)) {
                                            // 从左往右数, 将第 bit3 位置的bit值置为1
                                            codeWordsL3[cluster3] |=
                                                    (short) (1 << 8 << (8 - bit3 - 1));

                                            lookupEntriesL3.add(
                                                    nextNodeL3.value == null
                                                            ? 0
                                                            : idxTable.compute(
                                                                    nextNodeL3.value,
                                                                    (v_, idx_) -> {
                                                                        if (idx_ == null) {
                                                                            tree.resultList.add(v_);
                                                                            return tree.resultList
                                                                                            .size()
                                                                                    - 1;
                                                                        } else {
                                                                            return idx_;
                                                                        }
                                                                    }));

                                            countL3++;
                                            firstNodeL3 = nextNodeL3;
                                        }
                                        if (k3 > 0 && k3 % 8 == 0) {
                                            // 将 before 设置为本组中所有前面的簇中的 1 的个数
                                            codeWordsL3[cluster3] |= (short) (countL3);
                                        }
                                    }
                                    ChunkEntry chunkEntryL3 = new ChunkEntry();
                                    // TODO 实现稀疏节点的优化
                                    chunkEntryL3.codeWord = codeWordsL3;
                                    chunkEntryL3.lookupEntries = new int[lookupEntriesL3.size()];
                                    for (int tmpI = 0; tmpI < lookupEntriesL3.size(); tmpI++) {
                                        chunkEntryL3.lookupEntries[tmpI] =
                                                lookupEntriesL3.get(tmpI);
                                    }
                                    group3[j3] = chunkEntryL3;
                                }
                            }

                            firstNodeL2 = nextNodeL2;
                        }
                    }
                }
            }
        }

        return tree;
    }
}
