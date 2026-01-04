package com.github.abjfh;

import java.util.*;

/**
 * FPATreeV3 - 扁平化存储优化版本。
 *
 * <p>相比 FPATreeV2 的优化：
 *
 * <ul>
 *   <li>用原生数组替代 ArrayList，消除指针追逐
 *   <li>所有 Chunk 数据扁平化连续存储
 *   <li>提高缓存命中率，减少内存访问延迟
 * </ul>
 *
 * @param <V> 存储的值类型
 */
public class FPATreeV3<V> {
    // ========== 常量定义 ==========
    private static final int LAYER1_GROUP_COUNT = 1024;
    private static final int GROUP_SIZE = 64;

    private static final int TYPE_LEAF = 0;
    private static final int TYPE_DENSE = 1;
    private static final int TYPE_SPARSE = 2;
    private static final int DEFAULT_K = 8;

    // Dense/Sparse 切换阈值
    int K = DEFAULT_K;

    // ========== Layer 1: Root Chunk (保持不变) ==========
    int[][] rootChunk = new int[LAYER1_GROUP_COUNT][GROUP_SIZE];

    // ========== Layer 2/3: Dense Chunks (扁平化存储) ==========
    // 使用 List 动态收集所有 chunk 数据（连续存储）
    List<Integer> denseChunkDataList = new ArrayList<>(); // 所有 chunk 数据连续收集
    int[] denseChunkData; // 最终的数组（从 List 转换）
    int[] denseChunkOffsets; // 每个 chunk 在 denseChunkData 中的起始偏移
    int denseChunkCount; // dense chunk 总数

    // ========== Layer 2/3: Sparse Chunks (扁平化存储) ==========
    // 使用 List 动态收集，然后转换为数组
    List<Integer> sparseChunkDataList = new ArrayList<>(); // 临时收集
    int[] sparseChunkData; // 最终的数组（从 List 转换）
    int[] sparseChunkOffsets; // 每个 chunk 在 sparseChunkData 中的起始偏移
    int[] sparseChunkSizes; // 每个 sparse chunk 的条目数量
    int sparseChunkCount; // sparse chunk 总数

    // ========== 值存储 ==========
    List<V> resultList;

    // ========== 辅助数据 ==========
    static final byte[] INDEX_TABLE = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            INDEX_TABLE[i] = (byte) Integer.bitCount(i);
        }
    }

    // ========== 构建上下文 ==========
    private static class BuildContext<V> {
        FPATreeV3<V> tree;
        Map<ForwardingPortArray<?>, Integer> fpaCache; // FPA -> lookupEntry 缓存

        BuildContext(FPATreeV3<V> tree) {
            this.tree = tree;
            this.fpaCache = new java.util.HashMap<>();
        }
    }

    // ========== 预扫描统计信息 ==========
    private static class ChunkSizeInfo {
        int denseChunkCount;
        int totalDenseSize;
        int sparseChunkCount;
        int totalSparseSize;
    }

    /** 编码 lookupEntry。 */
    private static int encodeLookupEntry(int type, int index) {
        return (type << 30) | (index & 0x3FFFFFFF);
    }

    /** 获取值的索引（去重）。 */
    private static <V> int getValueIndex(
            V value, Hashtable<V, Integer> idxTable, FPATreeV3<V> tree) {
        if (value == null) {
            return 0;
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

    /** 预扫描，统计所有 chunk 的大小（递归处理所有层）。 */
    private static <V> ChunkSizeInfo preScanAndCount(ForwardingPortArray<V> fpa, int K) {
        ChunkSizeInfo info = new ChunkSizeInfo();
        Hashtable<ForwardingPortArray<?>, Boolean> visited = new Hashtable<>();

        for (int i = 0; i < fpa.table.size(); i++) {
            ForwardingPortArray.FPANode<V> node = fpa.table.get(i);
            if (node.next != null && !visited.containsKey(node.next)) {
                countLayer(node.next, info, visited, K);
            }
        }

        return info;
    }

    /** 递归统计一层的大小（包括所有子层）。 */
    private static <V> void countLayer(
            ForwardingPortArray<V> fpa,
            ChunkSizeInfo info,
            Hashtable<ForwardingPortArray<?>, Boolean> visited,
            int K) {
        // 如果已经访问过这个 FPA，跳过
        if (visited.containsKey(fpa)) {
            return;
        }
        visited.put(fpa, Boolean.TRUE);

        int totalEntries = 0;
        for (int j = 0; j < 4; j++) {
            int lookupCount = calculateLookupCount(fpa, j);
            totalEntries += lookupCount;
        }

        if (totalEntries > K) {
            // Dense chunk: header(16) + 4 (lookupCounts) + lookupEntries
            info.denseChunkCount++;
            info.totalDenseSize += 20 + totalEntries;
        } else {
            // Sparse chunk - 需要构建 BitTrie 才能确定大小
            BitTrie<Integer> trie = new BitTrie<>();
            for (int i = 0; i < fpa.table.size(); i++) {
                ForwardingPortArray.FPANode<V> node = fpa.table.get(i);
                int entry = (node.next == null) ? encodeLookupEntry(TYPE_LEAF, 0) : 0;
                byte[] bytes = {(byte) i};
                trie.put(bytes, 8, entry);
            }
            trie.compress();

            final int[] entryCount = {0};
            trie.preorderTraversalIterative((prefix, v) -> entryCount[0]++);

            info.sparseChunkCount++;
            // header(1) + triples(entryCount * 3)
            info.totalSparseSize += 1 + entryCount[0] * 3;
        }

        // 递归处理子层
        for (int i = 0; i < fpa.table.size(); i++) {
            ForwardingPortArray.FPANode<V> node = fpa.table.get(i);
            if (node.next != null) {
                countLayer(node.next, info, visited, K);
            }
        }
    }

    /** 计算 lookupEntries 数量。 */
    private static <V> int calculateLookupCount(ForwardingPortArray<V> fpa, int groupIdx) {
        int count = 0;
        ForwardingPortArray.FPANode<V> firstNode = null;

        for (int cluster = 0; cluster < 8; cluster++) {
            for (int bit = 0; bit < 8; bit++) {
                int k = groupIdx * 64 + cluster * 8 + bit;
                ForwardingPortArray.FPANode<V> node = fpa.table.get(k);
                if (!Objects.equals(firstNode, node)) {
                    firstNode = node;
                    count++;
                }
            }
        }
        return count;
    }

    /** 构建 FPATreeV3。 */
    public static <V> FPATreeV3<V> build(ForwardingPortArray<V> fpa) {
        return build(fpa, DEFAULT_K);
    }

    /** 构建 FPATreeV3（指定 K 值）。 */
    public static <V> FPATreeV3<V> build(ForwardingPortArray<V> fpa, int K) {
        // Phase 1: 预扫描（传入 K 值）
        ChunkSizeInfo info = preScanAndCount(fpa, K);

        // Phase 2: 分配数组
        FPATreeV3<V> tree = new FPATreeV3<>();
        tree.K = K; // 设置 K 值
        tree.denseChunkDataList = new ArrayList<>();
        tree.denseChunkOffsets = new int[info.denseChunkCount];
        tree.sparseChunkOffsets = new int[info.sparseChunkCount];
        tree.sparseChunkSizes = new int[info.sparseChunkCount];
        tree.resultList = new ArrayList<>();
        tree.resultList.add(null);

        BuildContext<V> ctx = new BuildContext<>(tree);
        Hashtable<V, Integer> idxTable = new Hashtable<>();

        // Phase 3: 填充 rootChunk
        for (int i = 0; i < fpa.table.size(); i++) {
            int group1 = i >> 6;
            int bit1 = i & 0b111111;
            ForwardingPortArray.FPANode<V> node = fpa.table.get(i);

            if (node.next == null) {
                int valueIdx = getValueIndex(node.value, idxTable, tree);
                tree.rootChunk[group1][bit1] = encodeLookupEntry(TYPE_LEAF, valueIdx);
            } else {
                tree.rootChunk[group1][bit1] = processLayer(node.next, idxTable, ctx);
            }
        }

        // Phase 4: 将 List 转换为扁平数组
        // 4.1 转换 dense chunks
        tree.denseChunkData = new int[tree.denseChunkDataList.size()];
        for (int i = 0; i < tree.denseChunkDataList.size(); i++) {
            tree.denseChunkData[i] = tree.denseChunkDataList.get(i);
        }

        // 4.2 转换 sparse chunks
        tree.sparseChunkData = new int[tree.sparseChunkDataList.size()];
        for (int i = 0; i < tree.sparseChunkDataList.size(); i++) {
            tree.sparseChunkData[i] = tree.sparseChunkDataList.get(i);
        }

        return tree;
    }

    /** 处理一层（Layer 2 或 Layer 3）。 */
    private static <V> int processLayer(
            ForwardingPortArray<V> fpa, Hashtable<V, Integer> idxTable, BuildContext<V> ctx) {

        // 检查缓存
        if (ctx.fpaCache.containsKey(fpa)) {
            return ctx.fpaCache.get(fpa);
        }

        // 构建临时 group 结构
        short[][] codeWordsArray = new short[4][8];
        List<List<Integer>> allLookupEntries = new ArrayList<>();

        for (int j = 0; j < 4; j++) {
            List<Integer> lookupEntries = new LinkedList<>();
            ForwardingPortArray.FPANode<V> firstNode = null;
            short[] codeWords = new short[8];
            int before = 0;

            for (int cluster = 0; cluster < 8; cluster++) {
                int bitset = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int k = j * 64 + cluster * 8 + bit;
                    ForwardingPortArray.FPANode<V> node = fpa.table.get(k);

                    if (!Objects.equals(firstNode, node)) {
                        bitset |= (1 << (7 - bit));
                        int entry;
                        if (node.next == null) {
                            entry =
                                    encodeLookupEntry(
                                            TYPE_LEAF,
                                            getValueIndex(node.value, idxTable, ctx.tree));
                        } else {
                            entry = processLayer(node.next, idxTable, ctx);
                        }
                        firstNode = node;
                        lookupEntries.add(entry);
                    }
                }
                codeWords[cluster] = (short) ((bitset << 8) | before);
                before = lookupEntries.size();
            }

            codeWordsArray[j] = codeWords;
            allLookupEntries.add(lookupEntries);
        }

        // 计算总条目数
        int totalEntries = 0;
        for (List<Integer> entries : allLookupEntries) {
            totalEntries += entries.size();
        }

        if (totalEntries > ctx.tree.K) {
            // Dense chunk
            int result = buildDenseChunk(codeWordsArray, allLookupEntries, ctx);
            ctx.fpaCache.put(fpa, result); // 缓存结果
            return result;
        } else {
            // Sparse chunk
            int result = buildSparseChunk(fpa, codeWordsArray, allLookupEntries, idxTable, ctx);
            ctx.fpaCache.put(fpa, result); // 缓存结果
            return result;
        }
    }

    /** 构建 Dense Chunk。 */
    private static <V> int buildDenseChunk(
            short[][] codeWordsArray, List<List<Integer>> allLookupEntries, BuildContext<V> ctx) {

        int base = ctx.tree.denseChunkDataList.size();
        int chunkIdx = ctx.tree.denseChunkCount;
        ctx.tree.denseChunkCount++;
        ctx.tree.denseChunkOffsets[chunkIdx] = base;

        // 写入 packed codeWords (4 ints)
        for (int j = 0; j < 4; j++) {
            short[] codeWords = codeWordsArray[j];
            int packed0 = (codeWords[0] & 0xFFFF) << 16 | (codeWords[1] & 0xFFFF);
            int packed1 = (codeWords[2] & 0xFFFF) << 16 | (codeWords[3] & 0xFFFF);
            int packed2 = (codeWords[4] & 0xFFFF) << 16 | (codeWords[5] & 0xFFFF);
            int packed3 = (codeWords[6] & 0xFFFF) << 16 | (codeWords[7] & 0xFFFF);

            ctx.tree.denseChunkDataList.add(packed0);
            ctx.tree.denseChunkDataList.add(packed1);
            ctx.tree.denseChunkDataList.add(packed2);
            ctx.tree.denseChunkDataList.add(packed3);
        }

        // 写入 lookupCounts
        for (int j = 0; j < 4; j++) {
            ctx.tree.denseChunkDataList.add(allLookupEntries.get(j).size());
        }

        // 写入 lookupEntries
        for (int j = 0; j < 4; j++) {
            ctx.tree.denseChunkDataList.addAll(allLookupEntries.get(j));
        }

        return encodeLookupEntry(TYPE_DENSE, chunkIdx);
    }

    /** 构建 Sparse Chunk。 */
    private static <V> int buildSparseChunk(
            ForwardingPortArray<V> fpa,
            short[][] codeWordsArray,
            List<List<Integer>> allLookupEntries,
            Hashtable<V, Integer> idxTable,
            BuildContext<V> ctx) {

        // 使用与 V2 相同的方式构建：从 codeWordsArray 构建临时 chunk，然后转换
        BitTrie<Integer> trie = new BitTrie<>();
        byte[] bytes = new byte[1];
        for (int i = 0; i < fpa.table.size(); i++) {
            // 从 codeWordsArray 获取 lookupEntry（与 searchLookupEntry 相同的逻辑）
            int groupIdx = i >> 6;
            int bitIdx = i & 0b111111;
            int cluster = bitIdx >> 3;
            int bit = bitIdx & 0b111;

            short codeWord = codeWordsArray[groupIdx][cluster];
            int bitset = (codeWord >> 8) & 0xFF;
            int before = codeWord & 0xFF;

            int shifted = bitset >> (8 - bit - 1);
            int onesInCluster = INDEX_TABLE[shifted];
            int lookupIdx = before + onesInCluster - 1;

            int lookupEntry =
                    (lookupIdx >= 0 && lookupIdx < allLookupEntries.get(groupIdx).size())
                            ? allLookupEntries.get(groupIdx).get(lookupIdx)
                            : 0;

            bytes[0] = (byte) i;
            trie.put(bytes, 8, lookupEntry);
        }

        trie.compress();

        List<int[]> entries = new ArrayList<>();
        trie.preorderTraversalIterative(
                (prefix, v) -> {
                    int codeWord = 0;
                    for (int i = 0; i < prefix.size(); i++) {
                        if (prefix.get(i)) {
                            codeWord |= (1 << (7 - i + 8));
                        }
                    }
                    codeWord |= (prefix.size() & 0xFF);
                    entries.add(new int[] {prefix.size(), codeWord, v});
                });

        entries.sort(
                (a, b) -> {
                    if (a[0] != b[0]) return b[0] - a[0];
                    return Integer.compare(a[1], b[1]);
                });

        int base = ctx.tree.sparseChunkDataList.size();
        int chunkIdx = ctx.tree.sparseChunkCount;
        ctx.tree.sparseChunkCount++;
        ctx.tree.sparseChunkOffsets[chunkIdx] = base;
        ctx.tree.sparseChunkSizes[chunkIdx] = entries.size();

        // 使用 List 动态收集数据
        ctx.tree.sparseChunkDataList.add(entries.size());
        for (int[] entry : entries) {
            ctx.tree.sparseChunkDataList.add(entry[0]);
            ctx.tree.sparseChunkDataList.add(entry[1]);
            ctx.tree.sparseChunkDataList.add(entry[2]);
        }

        return encodeLookupEntry(TYPE_SPARSE, chunkIdx);
    }

    // ========== 查询方法 ==========

    /** 查询 IP 地址对应的值（最长前缀匹配）。 */
    public V search(byte[] ipBytes) {
        if (ipBytes == null || ipBytes.length != 4) {
            return null;
        }

        int index16 = ((ipBytes[0] & 0xFF) << 8) | (ipBytes[1] & 0xFF);
        int group1 = index16 >> 6;
        int bit1 = index16 & 0b111111;

        int entry = rootChunk[group1][bit1];
        return resolveLookupEntryFast(entry, ipBytes);
    }

    private V resolveLookupEntryFast(int lookupEntry, byte[] ipBytes) {

        int nextLookupEntry = lookupEntry;
        for (int i = 2; i < ipBytes.length; i++) {
            int type = nextLookupEntry >>> 30;
            int index = nextLookupEntry & 0x3FFFFFFF;

            if (type == TYPE_LEAF) {
                return resultList.get(index);
            } else if (type == TYPE_DENSE) {
                int base = denseChunkOffsets[index];
                nextLookupEntry = searchInDenseChunkFast(base, ipBytes[i] & 0xFF);
            } else if (type == TYPE_SPARSE) {
                int base = sparseChunkOffsets[index];
                nextLookupEntry = searchInSparseChunkFast(base, ipBytes[i]);
            }
        }
        return resultList.get(nextLookupEntry);
    }

    private int searchInDenseChunkFast(int base, int index8) {
        int groupIdx = index8 >> 6;
        int bitIdx = index8 & 0b111111;

        int cluster = bitIdx >> 3;
        int bit = bitIdx & 0b111;

        // 解包 codeWord
        int packedIdx = groupIdx * 4 + (cluster >> 1);
        int packed = denseChunkData[base + packedIdx];
        short codeWord = (short) (((cluster & 1) == 0) ? (packed >> 16) : (packed & 0xFFFF));

        int bitset = (codeWord >> 8) & 0xFF;
        int before = codeWord & 0xFF;

        int shifted = bitset >> (8 - bit - 1);
        int onesInCluster = INDEX_TABLE[shifted];
        int lookupIdx = before + onesInCluster - 1;

        // 计算 lookupEntries 位置
        int lookupOffset = 20; // header size
        for (int i = 0; i < groupIdx; i++) {
            lookupOffset += denseChunkData[base + 16 + i];
        }
        lookupOffset += lookupIdx;

        return denseChunkData[base + lookupOffset];
    }

    private int searchInSparseChunkFast(int base, byte ipByte) {
        int entryCount = sparseChunkData[base];

        for (int i = 0; i < entryCount; i++) {
            int offset = base + 1 + i * 3;
            int prefixLen = sparseChunkData[offset];
            int codeWord = sparseChunkData[offset + 1];
            int lookupEntry = sparseChunkData[offset + 2];

            if (prefixLen == 0) {
                return lookupEntry;
            }

            // 修正：从高8位获取 mask，从低8位获取 prefixLen
            int prefixVal = (codeWord >> 8) & 0xFF; // 高8位是mask
            int ipPrefix = (ipByte & 0xFF) >>> (8 - prefixLen);
            int valPrefix = prefixVal >>> (8 - prefixLen);
            if (ipPrefix == valPrefix) {
                return lookupEntry;
            }
        }

        return 0;
    }
}
