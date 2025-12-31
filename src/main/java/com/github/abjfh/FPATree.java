package com.github.abjfh;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class FPATree<V> {
    // ==================== 常量定义 ====================

    // Lookup Entry 类型常量
    private static final int TYPE_LEAF = 0; // 叶子节点，指向resultList
    private static final int TYPE_COMPRESSED = 1; // 压缩分支，指向compressedChunkList
    private static final int TYPE_SPARSE = 2; // 稀疏分支，指向sparseChunkList

    // 位数相关常量
    private static final int INDEX_BITS = 30; // index占30位
    private static final int INDEX_MASK = 0x3FFFFFFF; // index 的掩码

    // 层级结构常量
    private static final int LAYER1_DEPTH = 16; // Layer 1 深度
    private static final int LAYER23_DEPTH = 8; // Layer 2/3 深度
    private static final int LAYER1_GROUP_COUNT = 1024; // Layer 1 组数
    private static final int LAYER23_GROUP_COUNT = 4; // Layer 2/3 组数
    private static final int GROUP_SIZE = 64; // 每组元素数量
    private static final int CLUSTER_COUNT = 8; // 每组簇数量

    // 位划分常量
    private static final int GROUP_BIT_BITS = 6; // 组索引占6位
    private static final int CLUSTER_BITS = 3; // 簇索引占3位
    private static final int BIT_BITS = 3; // 位索引占3位
    private static final int CODE_WORD_SIZE = 2; // Code Word大小（字节）

    // 压缩结构常量
    private static final int COMPRESSED_TABLE_SIZE = 256; // 压缩表大小

    // ==================== 数据结构 ====================

    /** Layer 1 根chunk: LAYER1_GROUP_COUNT组 × GROUP_SIZE元素 */
    int[][] rootChunk;

    List<CompressedGroup[]> compressedChunkList = new ArrayList<>();
    List<SparseEntry[]> sparseChunkList = new ArrayList<>();
    List<V> resultList = new ArrayList<>();

    // Index Table: 256条目，记录每种8位组合中1的个数
    static final byte[] INDEX_TABLE = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            INDEX_TABLE[i] = (byte) Integer.bitCount(i);
        }
    }

    public FPATree() {
        resultList.add(null);
        rootChunk = new int[LAYER1_GROUP_COUNT][GROUP_SIZE];
    }

    static class SparseEntry {
        byte prefix;
        byte mask;

        int lookupEntry;
    }

    /** 压缩组结构 - 用于Layer 2/3 */
    static class CompressedGroup {
        // Code Word数组: CLUSTER_COUNT个簇，每个2字节(16位)
        // 高8位: bitset(该簇8个元素中哪些位置有非默认值)
        // 低8位: before(所有前面簇中1的总个数)
        short[] codeWords; // size=CLUSTER_COUNT

        // 默认值（当位图为0时使用）
        int defaultValue;

        // 压缩的FPA: 只存储非默认值
        int[] compressedFPA;

        /** 从Code Word中提取bitset（高8位） */
        static int getBitset(short codeWord) {
            return (codeWord >> 8) & 0xFF;
        }

        /** 从Code Word中提取before（低8位） */
        static int getBefore(short codeWord) {
            return codeWord & 0xFF;
        }
    }

    // ==================== 辅助方法 ====================

    /** 编码查找表项：type(2位) + index(30位) */
    private int encodeLookupEntry(int type, int index) {
        return (type << INDEX_BITS) | index;
    }

    /** 判断稀疏性：与根节点不同的节点数 <= K */
    private boolean isSparse(ForwardingPortArray<V> fpa, int K) {
        if (K <= 0) return false;

        ForwardingPortArray.FPANode<V> root = fpa.table.get(0);
        int nonRootCount = 0;

        for (ForwardingPortArray.FPANode<V> node : fpa.table) {
            if (!node.equals(root)) {
                nonRootCount++;
                if (nonRootCount > K) return false;
            }
        }
        return true;
    }

    /** 获取value在resultList中的索引，不存在则添加 */
    private int getValueIndex(V value, Hashtable<V, Integer> idxTable) {
        return value == null
                ? 0
                : idxTable.compute(
                        value,
                        (v, idx) -> {
                            if (idx == null) {
                                resultList.add(v);
                                return resultList.size() - 1;
                            }
                            return idx;
                        });
    }

    /** 分配新的压缩chunk（用于Layer 2/3） */
    private CompressedGroup[] allocateCompressedChunk() {
        CompressedGroup[] newChunk = new CompressedGroup[LAYER23_GROUP_COUNT];
        compressedChunkList.add(newChunk);
        return newChunk;
    }

    /** 从int中提取type（高INDEX_BITS位） */
    private int getType(int lookupEntry) {
        return lookupEntry >>> INDEX_BITS;
    }

    /** 从int中提取index（低INDEX_BITS位） */
    private int getIndex(int lookupEntry) {
        return lookupEntry & INDEX_MASK;
    }

    /** 根据节点构建 lookupEntry（带缓存优化） */
    private int buildLookupEntry(
            ForwardingPortArray.FPANode<V> node,
            Hashtable<V, Integer> idxTable,
            int K,
            Map<ForwardingPortArray.FPANode<V>, Integer> nodeCache) {
        // 检查缓存
        Integer cached = nodeCache.get(node);
        if (cached != null) {
            return cached;
        }

        int result;
        if (node.next == null) {
            result = encodeLookupEntry(TYPE_LEAF, getValueIndex(node.value, idxTable));
        } else {
            boolean sparse = isSparse(node.next, K);
            if (sparse) {
                int sparseIdx = processSparseBranch(node.next, idxTable, K, nodeCache);
                result = encodeLookupEntry(TYPE_SPARSE, sparseIdx);
            } else {
                // Layer 2/3使用压缩结构
                CompressedGroup[] newChunk = allocateCompressedChunk();
                buildCompressedGroups(node.next, newChunk, idxTable, K, nodeCache);
                result = encodeLookupEntry(TYPE_COMPRESSED, compressedChunkList.size() - 1);
            }
        }

        // 存入缓存
        nodeCache.put(node, result);
        return result;
    }

    /** 构建压缩组结构（带缓存优化） */
    private void buildCompressedGroups(
            ForwardingPortArray<V> fpa,
            CompressedGroup[] groups,
            Hashtable<V, Integer> idxTable,
            int K,
            Map<ForwardingPortArray.FPANode<V>, Integer> nodeCache) {

        int tableSize = COMPRESSED_TABLE_SIZE;

        // 第一遍: 统计每个簇的非默认值数量，并确定默认值
        int[][] nonDefaultCount = new int[LAYER23_GROUP_COUNT][CLUSTER_COUNT];
        int[] defaultValues = new int[LAYER23_GROUP_COUNT];

        for (int idx = 0; idx < tableSize; idx++) {
            int groupIdx = idx >> GROUP_BIT_BITS; // 0-3
            int clusterIdx = (idx >> CLUSTER_BITS) & 0x7; // 0-7
            int bitIdx = idx & 0x7; // 0-7

            ForwardingPortArray.FPANode<V> node = fpa.table.get(idx);

            // 为每个组的第一个元素设置默认值
            if (idx == 0 || (groupIdx > 0 && clusterIdx == 0 && bitIdx == 0)) {
                if (node.next == null) {
                    defaultValues[groupIdx] =
                            encodeLookupEntry(TYPE_LEAF, getValueIndex(node.value, idxTable));
                } else {
                    defaultValues[groupIdx] = buildLookupEntry(node, idxTable, K, nodeCache);
                }
            }
        }

        // 第二遍: 统计非默认值数量
        for (int idx = 0; idx < tableSize; idx++) {
            int groupIdx = idx >> GROUP_BIT_BITS;
            int clusterIdx = (idx >> CLUSTER_BITS) & 0x7;

            ForwardingPortArray.FPANode<V> node = fpa.table.get(idx);
            int lookupEntry = buildLookupEntry(node, idxTable, K, nodeCache);

            if (lookupEntry != defaultValues[groupIdx]) {
                nonDefaultCount[groupIdx][clusterIdx]++;
            }
        }

        // 第三遍: 构建压缩结构
        for (int groupIdx = 0; groupIdx < LAYER23_GROUP_COUNT; groupIdx++) {
            groups[groupIdx] = new CompressedGroup();
            groups[groupIdx].defaultValue = defaultValues[groupIdx];
            groups[groupIdx].codeWords = new short[CLUSTER_COUNT];

            // 计算总非默认值数量
            int totalNonDefault = 0;
            for (int c = 0; c < CLUSTER_COUNT; c++) {
                totalNonDefault += nonDefaultCount[groupIdx][c];
            }
            groups[groupIdx].compressedFPA = new int[totalNonDefault];

            // 临时计数器，记录每个簇已填充的非默认值数量
            int[] filledCount = new int[CLUSTER_COUNT];

            // 填充 compressedFPA和设置bitset
            for (int idx = 0; idx < tableSize; idx++) {
                int currentGroup = idx >> GROUP_BIT_BITS;
                if (currentGroup != groupIdx) continue;

                int clusterIdx = (idx >> CLUSTER_BITS) & 0x7;
                int bitIdx = idx & 0x7;

                ForwardingPortArray.FPANode<V> node = fpa.table.get(idx);
                int lookupEntry = buildLookupEntry(node, idxTable, K, nodeCache);

                if (lookupEntry != defaultValues[groupIdx]) {
                    // 设置 bitset
                    int mask = 1 << (7 - bitIdx);
                    groups[groupIdx].codeWords[clusterIdx] |= (short) (mask << 8);

                    // 计算在 compressedFPA 中的位置
                    int clusterOffset = 0;
                    for (int c = 0; c < clusterIdx; c++) {
                        clusterOffset += nonDefaultCount[groupIdx][c];
                    }
                    int fpaIdx = clusterOffset + filledCount[clusterIdx];
                    groups[groupIdx].compressedFPA[fpaIdx] = lookupEntry;

                    filledCount[clusterIdx]++;
                }
            }

            // 填充 before 字段
            int cumulativeOnes = 0;
            for (int c = 0; c < CLUSTER_COUNT; c++) {
                int bitset = CompressedGroup.getBitset(groups[groupIdx].codeWords[c]);
                groups[groupIdx].codeWords[c] |= (short) cumulativeOnes;
                cumulativeOnes += INDEX_TABLE[bitset];
            }
        }
    }

    // ==================== 核心递归方法 ====================

    /** 递归处理 ForwardingPortArray 并构建 FPATree */
    private void processLevel(
            ForwardingPortArray<V> fpa,
            int[][] targetChunk,
            Hashtable<V, Integer> idxTable,
            int K,
            Map<ForwardingPortArray.FPANode<V>, Integer> nodeCache) {
        int tableSize = fpa.table.size();

        for (int idx = 0; idx < tableSize; idx++) {
            ForwardingPortArray.FPANode<V> node = fpa.table.get(idx);

            int groupIdx = idx >> GROUP_BIT_BITS;
            int bitIdx = idx & (GROUP_SIZE - 1);

            if (node.next == null) {
                // 叶子节点
                int valueIdx = getValueIndex(node.value, idxTable);
                targetChunk[groupIdx][bitIdx] = encodeLookupEntry(TYPE_LEAF, valueIdx);
            } else {
                // 非叶子节点：判断稀疏性
                boolean sparse = isSparse(node.next, K);
                if (sparse) {
                    int sparseIdx = processSparseBranch(node.next, idxTable, K, nodeCache);
                    targetChunk[groupIdx][bitIdx] = encodeLookupEntry(TYPE_SPARSE, sparseIdx);
                } else {
                    processDenseBranch(
                            node.next, targetChunk, groupIdx, bitIdx, idxTable, K, nodeCache);
                }
            }
        }
    }

    /** 处理稀疏分支，返回sparse索引（带缓存优化） */
    private int processSparseBranch(
            ForwardingPortArray<V> nextFPA,
            Hashtable<V, Integer> idxTable,
            int K,
            Map<ForwardingPortArray.FPANode<V>, Integer> nodeCache) {
        // 收集稀疏条目（所有与根节点不同的节点）
        ForwardingPortArray.FPANode<V> root = nextFPA.table.get(0);
        List<SparseEntry> sparseEntriesList = new ArrayList<>();

        // 添加根节点作为默认值（mask=-1保证排序时在最后）
        SparseEntry defaultEntry = new SparseEntry();
        defaultEntry.prefix = -1;
        defaultEntry.mask = -1; // -1确保排序后在最后
        defaultEntry.lookupEntry = buildLookupEntry(root, idxTable, K, nodeCache);
        sparseEntriesList.add(defaultEntry);

        // 添加非根节点的稀疏条目
        for (int idx = 0; idx < nextFPA.table.size(); idx++) {
            ForwardingPortArray.FPANode<V> node = nextFPA.table.get(idx);
            if (!node.equals(root)) {
                SparseEntry entry = new SparseEntry();
                entry.prefix = (byte) idx;
                entry.mask = (byte) (32 - Integer.numberOfLeadingZeros(nextFPA.table.size() - 1));
                entry.lookupEntry = buildLookupEntry(node, idxTable, K, nodeCache);
                sparseEntriesList.add(entry);
            }
        }

        // 按mask降序排序（默认值mask=-1自动排在最后）
        sparseEntriesList.sort((a, b) -> Byte.compare(b.mask, a.mask));

        SparseEntry[] sparseEntries = sparseEntriesList.toArray(new SparseEntry[0]);
        int sparseIdx = sparseChunkList.size();
        sparseChunkList.add(sparseEntries);

        return sparseIdx;
    }

    /** 处理非稀疏分支（带缓存优化） */
    private void processDenseBranch(
            ForwardingPortArray<V> nextFPA,
            int[][] parentChunk,
            int parentChunkIdx,
            int parentInChunkIdx,
            Hashtable<V, Integer> idxTable,
            int K,
            Map<ForwardingPortArray.FPANode<V>, Integer> nodeCache) {
        // Layer 2/3 使用压缩结构
        {
            CompressedGroup[] newCompressedChunk = allocateCompressedChunk();
            int newChunkIdx = compressedChunkList.size() - 1;

            // 递归处理下一层（使用缓存避免重复计算）
            buildCompressedGroups(nextFPA, newCompressedChunk, idxTable, K, nodeCache);

            // 在父chunk中设置指向压缩chunk的指针
            parentChunk[parentChunkIdx][parentInChunkIdx] =
                    encodeLookupEntry(TYPE_COMPRESSED, newChunkIdx);
        }
    }

    public static <V> FPATree<V> build(ForwardingPortArray<V> root) {
        return build(root, 3);
    }

    /**
     * 构建FPATree
     *
     * <p>查找表项由两部分组成：type(2位) + index(30位)。
     *
     * <p>类型定义：
     *
     * <ul>
     *   <li>TYPE_LEAF(0): 叶子节点，指向resultList
     *   <li>TYPE_COMPRESSED(1): 压缩分支，指向compressedChunkList
     *   <li>TYPE_SPARSE(2): 稀疏分支，指向sparseChunkList
     * </ul>
     */
    public static <V> FPATree<V> build(ForwardingPortArray<V> root, int K) {
        FPATree<V> fpaTree = new FPATree<>();

        Hashtable<V, Integer> idxTable = new Hashtable<>();
        // 节点缓存：避免重复处理相同的子树
        IdentityHashMap<ForwardingPortArray.FPANode<V>, Integer> nodeCache =
                new IdentityHashMap<>();

        // 处理Layer 1（深度LAYER1_DEPTH，65536条目）
        fpaTree.processLevel(root, fpaTree.rootChunk, idxTable, K, nodeCache);

        assert fpaTree.resultList.size() < (1 << INDEX_BITS);
        return fpaTree;
    }

    /**
     * 在chunk中查找
     *
     * @param chunk 要查找的chunk
     * @param ipAddress IP地址（32位整数）
     * @return 查找结果
     */
    private V lookupInChunk(int[][] chunk, int ipAddress) {
        int idx = (ipAddress >> (32 - FPATree.LAYER1_DEPTH)) & ((1 << FPATree.LAYER1_DEPTH) - 1);
        int groupIdx = idx >> GROUP_BIT_BITS;
        int bitIdx = idx & (GROUP_SIZE - 1);

        int lookupEntry = chunk[groupIdx][bitIdx];
        return followLookupEntry(lookupEntry, ipAddress, FPATree.LAYER1_DEPTH);
    }

    /**
     * 在稀疏chunk中查找
     *
     * <p>条目按mask降序排序（最长前缀优先），最后一个条目为默认值（mask=-1）
     *
     * @param sparseIdx sparse索引
     * @param ipAddress IP地址（32位整数）
     * @param bitOffset 当前位偏移
     * @return 查找结果
     */
    private V lookupInSparse(int sparseIdx, int ipAddress, int bitOffset) {
        SparseEntry[] entries = sparseChunkList.get(sparseIdx);

        // 按mask降序遍历，找到匹配就返回（最长前缀匹配）
        for (int i = 0; i < entries.length - 1; i++) {
            SparseEntry entry = entries[i];
            int prefix = entry.prefix & 0xFF;
            int mask = entry.mask; // byte会自动符号扩展为 int

            // 获取对应位的值
            int bits = (ipAddress >> (32 - bitOffset - mask)) & ((1 << mask) - 1);

            if (bits == prefix) {
                return followLookupEntry(entry.lookupEntry, ipAddress, bitOffset + mask);
            }
        }

        // 没有匹配任何条目，使用最后一个（默认值）
        SparseEntry defaultEntry = entries[entries.length - 1];
        return followLookupEntry(defaultEntry.lookupEntry, ipAddress, bitOffset);
    }

    /**
     * 在压缩组中查找
     *
     * @param compressedChunk 压缩chunk
     * @param ipAddress IP地址（32位整数）
     * @param bitOffset 当前位偏移
     * @param localIdx 组内索引(0-255)
     * @return 查找结果
     */
    private V lookupInCompressedGroup(
            CompressedGroup[] compressedChunk, int ipAddress, int bitOffset, int localIdx) {
        int groupIdx = localIdx >> GROUP_BIT_BITS; // 0-3
        CompressedGroup group = compressedChunk[groupIdx];

        int clusterIdx = (localIdx >> CLUSTER_BITS) & 0x7; // 0-7
        int bitIdx = localIdx & 0x7; // 0-7

        short codeWord = group.codeWords[clusterIdx];
        int bitset = CompressedGroup.getBitset(codeWord);
        int before = CompressedGroup.getBefore(codeWord);

        // 优化：整个 cluster 都是默认值
        if (bitset == 0) {
            return followLookupEntry(group.defaultValue, ipAddress, bitOffset + LAYER23_DEPTH);
        }

        // 检查该位是否为非默认值
        int mask = 1 << (7 - bitIdx);
        if ((bitset & mask) == 0) {
            // 位为0，使用默认值（内联 LEAF 处理以减少方法调用）
            return followLookupEntry(group.defaultValue, ipAddress, bitOffset + LAYER23_DEPTH);
        }

        // 位为1，从compressedFPA中获取
        // 计算簇内该位置之前的1的个数
        int shifted = bitset >> (CLUSTER_COUNT - bitIdx);
        int onesInCluster = INDEX_TABLE[shifted & 0xFF];

        int fpaIdx = before + onesInCluster;
        int lookupEntry = group.compressedFPA[fpaIdx];

        return followLookupEntry(lookupEntry, ipAddress, bitOffset + LAYER23_DEPTH);
    }

    /** 跟随lookupEntry进行查找 */
    private V followLookupEntry(int lookupEntry, int ipAddress, int bitOffset) {
        if (lookupEntry == 0) {
            return null;
        }
        int type = getType(lookupEntry);
        int index = getIndex(lookupEntry);
        if (type == TYPE_LEAF) {
            return resultList.get(index);
        }

        switch (type) {
            case TYPE_COMPRESSED:
                // 计算在压缩 chunk中的索引
                int idx = (ipAddress >> (32 - bitOffset - LAYER23_DEPTH)) & 0xFF;
                return lookupInCompressedGroup(
                        compressedChunkList.get(index), ipAddress, bitOffset, idx);
            case TYPE_SPARSE:
                return lookupInSparse(index, ipAddress, bitOffset);
            default:
                return null;
        }
    }

    /**
     * 根据IP地址查找路由信息
     *
     * @param ipAddress IP地址（4字节数组，大端序）
     * @return 路由信息，未找到返回null
     */
    public V lookup(byte[] ipAddress) {
        if (ipAddress == null || ipAddress.length != 4) {
            return null;
        }

        // 转换为32位整数（大端序）
        int ip = 0;
        for (int i = 0; i < 4; i++) {
            ip = (ip << 8) | (ipAddress[i] & 0xFF);
        }

        // 从根chunk开始查找
        return lookupInChunk(rootChunk, ip);
    }

    /**
     * 根据IP地址查找路由信息（32位整数版本）
     *
     * @param ipAddress IP地址（32位整数）
     * @return 路由信息，未找到返回null
     */
    public V lookup(int ipAddress) {
        return lookupInChunk(rootChunk, ipAddress);
    }
}
