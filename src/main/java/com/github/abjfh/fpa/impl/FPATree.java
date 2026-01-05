package com.github.abjfh.fpa.impl;

import com.github.abjfh.fpa.IpSearcher;

import java.io.Serializable;
import java.util.*;

public class FPATree<V> implements IpSearcher<V>, Serializable {
    // ========== 常量定义 ==========

    private static final int TYPE_LEAF = 0;
    private static final int TYPE_DENSE = 1;
    private static final int TYPE_SPARSE = 2;
    private static final int DEFAULT_K = 3;

    // ========== Layer 1: Root Chunk ==========
    private final int[] rootChunk = new int[65536];

    // ========== Layer 2/3: Chunk Array ==========
    private short[] denseChunkCodes;
    private int[][] denseChunkLookupEntries;
    private List<SparseChunkEntry[]> sparseChunkList;

    private List<V> resultList;

    // ========== 辅助数据 ==========
    static final byte[] INDEX_TABLE = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            INDEX_TABLE[i] = (byte) Integer.bitCount(i);
        }
    }

    static class DenseChunkEntry {
        short[] codeWords = new short[32];
        int[] lookupEntries;
    }

    static class SparseChunkEntry implements Comparable<SparseChunkEntry>, Serializable {
        byte prefix;
        byte mask;
        int lookupEntry;

        @Override
        public int compareTo(SparseChunkEntry o) {
            return Integer.bitCount(mask) - Integer.bitCount(o.mask);
        }
    }

    @Override
    public V search(byte[] ipBytes) {
        int lookupEntry = rootChunk[(ipBytes[0] & 0xFF) << 8 | ipBytes[1] & 0xFF];
        int type, index;
        int byteIdx = 2;
        do {
            if (lookupEntry == 0) {
                return null;
            }
            type = lookupEntry >>> 30;
            index = lookupEntry & 0x3FFFFFFF;
            if (type == TYPE_LEAF) {
                return resultList.get(index);
            } else if (type == TYPE_DENSE) {
                lookupEntry = search(ipBytes[byteIdx++], index);
            } else if (type == TYPE_SPARSE) {
                lookupEntry = search(ipBytes[byteIdx++], sparseChunkList.get(index));
            } else {
                return null;
            }
        } while (true);
    }

    public void printMemoryStats() {
        System.out.println("========== FPATree 内存统计 ==========");
        System.out.println();

        // Layer 1: Root Chunk
        long rootChunkMemory = rootChunk.length * 4L;
        System.out.println("【Layer 1: Root Chunk】");
        System.out.println("  大小: " + rootChunk.length + " 个 int");
        System.out.println("  内存: " + formatBytes(rootChunkMemory));
        System.out.println();

        // Layer 2/3: Dense Chunks
        System.out.println("【Layer 2/3: Dense Chunks】");
        if (denseChunkCodes != null) {
            long codesMemory = denseChunkCodes.length * 2L;
            System.out.println("  denseChunkCodes: " + denseChunkCodes.length + " 个 short");
            System.out.println("  内存: " + formatBytes(codesMemory));
        }
        if (denseChunkLookupEntries != null) {
            long lookupMemory = 0;
            int totalEntries = 0;
            for (int[] entries : denseChunkLookupEntries) {
                if (entries != null) {
                    lookupMemory += entries.length * 4L;
                    totalEntries += entries.length;
                }
            }
            System.out.println(
                    "  denseChunkLookupEntries: "
                            + denseChunkLookupEntries.length
                            + " 个 chunk, "
                            + totalEntries
                            + " 个 lookupEntry");
            System.out.println("  内存: " + formatBytes(lookupMemory));
        }
        System.out.println();

        // Layer 2/3: Sparse Chunks
        System.out.println("【Layer 2/3: Sparse Chunks】");
        if (sparseChunkList != null) {
            long sparseMemory = 0;
            int totalEntries = 0;
            for (SparseChunkEntry[] entries : sparseChunkList) {
                if (entries != null) {
                    sparseMemory += entries.length * 6L;
                    totalEntries += entries.length;
                }
            }
            System.out.println(
                    "  sparseChunkList: "
                            + sparseChunkList.size()
                            + " 个 chunk, "
                            + totalEntries
                            + " 个 entry");
            System.out.println("  内存: " + formatBytes(sparseMemory));
        }
        System.out.println();

        // Result List
        System.out.println("【Result List】");
        if (resultList != null) {
            long resultMemory = resultList.size() * 8L; // 对象引用估算
            System.out.println("  大小: " + resultList.size() + " 个元素");
            System.out.println("  内存: " + formatBytes(resultMemory) + " (引用数组，不包含实际对象)");
        }
        System.out.println();

        // 总计
        long totalMemory = rootChunkMemory;
        if (denseChunkCodes != null) totalMemory += denseChunkCodes.length * 2L;
        if (denseChunkLookupEntries != null) {
            for (int[] entries : denseChunkLookupEntries) {
                if (entries != null) totalMemory += entries.length * 4L;
            }
        }
        if (sparseChunkList != null) {
            for (SparseChunkEntry[] entries : sparseChunkList) {
                if (entries != null) totalMemory += entries.length * 6L;
            }
        }
        if (resultList != null) totalMemory += resultList.size() * 8L;

        System.out.println("======================================");
        System.out.println("总内存: " + formatBytes(totalMemory));
        System.out.println("======================================");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private int search(byte index8, int baseDenseChunkIdx) {
        int idx = (index8 & 0xFF) >>> 3;
        short codeWord = denseChunkCodes[(baseDenseChunkIdx << 5) + idx];
        int before = codeWord & 0xFF;
        int onesInCluster = INDEX_TABLE[((codeWord >>> 8) & 0xFF) >>> (7 - index8 & 0b111)];
        int lookupIdx = before + onesInCluster - 1;
        return denseChunkLookupEntries[baseDenseChunkIdx][lookupIdx];
    }

    private static int search(byte index8, DenseChunkEntry denseChunkEntry) {
        int idx = (index8 & 0xFF) >>> 3;
        short codeWord = denseChunkEntry.codeWords[idx];
        int before = codeWord & 0xFF;
        int onesInCluster = INDEX_TABLE[((codeWord >>> 8) & 0xFF) >>> (7 - index8 & 0b111)];
        int lookupIdx = before + onesInCluster - 1;
        return denseChunkEntry.lookupEntries[lookupIdx];
    }

    private static int search(byte index8, SparseChunkEntry[] sparseChunkEntries) {
        for (SparseChunkEntry entry : sparseChunkEntries) {
            if (entry.prefix == (index8 & entry.mask)) {
                return entry.lookupEntry;
            }
        }
        return 0;
    }

    public static <V> Builder<V> Builder() {
        return new Builder<>();
    }

    public static class Builder<V> {
        Hashtable<V, Integer> idxTable = new Hashtable<>();
        List<V> resultList = new ArrayList<>();

        {
            resultList.add(null);
        }

        List<DenseChunkEntry> denseChunkList = new ArrayList<>();
        List<SparseChunkEntry[]> sparseChunkList = new ArrayList<>();
        FPATree<V> tree;

        ForwardingPortArray<V> fpa_root;
        int K = DEFAULT_K;
        short[] denseChunkCodes;
        int[][] denseChunkLookupEntries;

        public Builder<V> K(int K) {
            this.K = K;
            return this;
        }

        public Builder<V> fpa(ForwardingPortArray<V> fpa_root) {
            this.fpa_root = fpa_root;
            return this;
        }

        public FPATree<V> build() {
            tree = new FPATree<>();
            int size_l1 = fpa_root.table.size();
            for (int i = 0; i < size_l1; i++) {
                tree.rootChunk[i] = processLookupEntry(fpa_root.table.get(i));
            }
            transformDenseChunk();
            tree.denseChunkCodes = denseChunkCodes;
            tree.denseChunkLookupEntries = denseChunkLookupEntries;
            tree.sparseChunkList = sparseChunkList;
            tree.resultList = resultList;
            return tree;
        }

        // 将 DenseChunkEntry展开
        private void transformDenseChunk() {
            int size = denseChunkList.size();
            denseChunkCodes = new short[size * 32];
            denseChunkLookupEntries = new int[size][];
            for (int i = 0; i < denseChunkList.size(); i++) {
                DenseChunkEntry denseChunkEntry = denseChunkList.get(i);
                System.arraycopy(denseChunkEntry.codeWords, 0, denseChunkCodes, i * 32, 32);
                denseChunkLookupEntries[i] = denseChunkEntry.lookupEntries;
            }
        }

        /**
         * @param root L2/3的FPA根节点
         * @return 编码后的 32 位整数
         */
        private int processLayer(ForwardingPortArray<V> root) {

            DenseChunkEntry denseChunkEntry = new DenseChunkEntry();
            LinkedList<Integer> lookupEntries = new LinkedList<>();
            ForwardingPortArray.FPANode<V> p1 = null;
            int before = 0;
            for (int i = 0; i < 32; i++) {
                for (int j = 0; j < 8; j++) {
                    ForwardingPortArray.FPANode<V> p2 = root.table.get(i * 8 + j);
                    if (!Objects.equals(p1, p2)) {
                        p1 = p2;
                        lookupEntries.add(processLookupEntry(p1));
                        denseChunkEntry.codeWords[i] |= (short) (1 << (7 - j));
                    }
                }
                denseChunkEntry.codeWords[i] <<= 8;
                denseChunkEntry.codeWords[i] |= (short) before;
                before = lookupEntries.size();
            }
            denseChunkEntry.lookupEntries =
                    lookupEntries.stream().mapToInt(Integer::intValue).toArray();

            if (lookupEntries.size() <= K * 4) {
                // 尝试退化到稀疏结构
                int layerIdx = processSparseLayer(root, denseChunkEntry);
                if (layerIdx >= 0) {
                    return encodeLookupEntry(TYPE_SPARSE, layerIdx);
                }
            }

            int lookupEntry = encodeLookupEntry(TYPE_DENSE, denseChunkList.size());
            denseChunkList.add(denseChunkEntry);
            return lookupEntry;
        }

        private int processSparseLayer(
                ForwardingPortArray<V> root, DenseChunkEntry denseChunkEntry) {
            BitTrie<Integer> trie = new BitTrie<>();
            byte[] bytes = new byte[1];
            for (int i = 0; i < root.table.size(); i++) {
                bytes[0] = (byte) i; // 把索引i作为key（0-255）
                int lookupEntry = search(bytes[0], denseChunkEntry);
                trie.put(bytes, 8, lookupEntry);
            }
            trie.compress();

            LinkedList<SparseChunkEntry> sparseChunkEntries = new LinkedList<>();
            trie.preorderTraversalIterative(
                    (prefix, v) -> {
                        if (v == 0) {
                            return;
                        }
                        SparseChunkEntry entry = new SparseChunkEntry();
                        for (int i = 0; i < prefix.size(); i++) {
                            if (prefix.get(i)) {
                                entry.prefix |= (byte) (1 << (7 - i));
                            }
                            entry.mask |= (byte) (1 << (7 - i));
                        }
                        entry.prefix &= entry.mask;
                        entry.lookupEntry = v;
                        sparseChunkEntries.add(entry);
                    });
            if (sparseChunkEntries.size() > K) {
                return -1;
            }

            Collections.sort(sparseChunkEntries);
            SparseChunkEntry[] sparseChunkArray =
                    sparseChunkEntries.toArray(new SparseChunkEntry[0]);

            sparseChunkList.add(sparseChunkArray);
            return sparseChunkList.size() - 1;
        }

        private int processLookupEntry(ForwardingPortArray.FPANode<V> fpaNode) {
            if (fpaNode.next == null) {
                // [情况1] 叶子节点：直接存储值索引
                return encodeLookupEntry(TYPE_LEAF, getValueIndex(fpaNode.value));
            } else {
                // [情况2] 有子树
                return processLayer(fpaNode.next);
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

        private int getValueIndex(V value) {
            if (value == null) {
                return 0; // null 值索引为 0
            }
            return idxTable.compute(
                    value,
                    (v, existingIdx) -> {
                        if (existingIdx == null) {
                            int vIdx = resultList.size();
                            resultList.add(v);
                            return vIdx;
                        }
                        return existingIdx;
                    });
        }
    }
}
