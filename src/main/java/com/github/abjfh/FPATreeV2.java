package com.github.abjfh;

import inet.ipaddr.AddressStringException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
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
        // bitset 8 | before 8 或 prefix 8 | mask 8
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

            ForwardingPortArray.FPANode<V> firstNode = fpa.table.get(j * 64);
            int count = 0;

            for (int k = 0; k < 64; k++) {
                int idx = j * 64 + k;
                int cluster = (idx >> 3) & 0b111;
                int bit = idx & 0b111;

                ForwardingPortArray.FPANode<V> nextNode = fpa.table.get(idx);

                if (!Objects.equals(firstNode, nextNode)) {
                    // 设置 bitset
                    codeWords[cluster] |= (short) (1 << 8 << (8 - bit - 1));

                    // 处理节点
                    int lookupEntry;
                    if (nextNode.next == null) {
                        // 最后一层或叶子节点：存储值索引
                        int valueIdx = getValueIndex(nextNode.value, idxTable, tree);
                        lookupEntry = encodeLookupEntry(TYPE_LEAF, valueIdx);
                    } else {
                        // 有子树：递归处理下一层
                        int chunkIdx = processLayer(nextNode.next, idxTable, tree);
                        lookupEntry = encodeLookupEntry(TYPE_DENSE, chunkIdx);
                    }

                    lookupEntries.add(lookupEntry);
                    count++;
                    firstNode = nextNode;
                }

                // 每 8 个元素设置 before 值
                if (k > 0 && k % 8 == 0) {
                    codeWords[cluster] |= (short) count;
                }
            }

            ChunkEntry entry = new ChunkEntry();
            entry.codeWord = codeWords;
            entry.lookupEntries = lookupEntries.stream().mapToInt(Integer::intValue).toArray();
            group[j] = entry;
        }

        // 将 group 加入 chunkList
        int chunkIdx = tree.chunkList.size();
        tree.chunkList.add(group);
        return chunkIdx;
    }

    public static <V> FPATreeV2<V> build(ForwardingPortArray<V> fpa) {

        Hashtable<V, Integer> idxTable = new Hashtable<>();
        FPATreeV2<V> tree = new FPATreeV2<>();
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
     * 主函数：使用 aspat.csv 数据测试 FPATreeV2 构建
     */
    public static void main(String[] args) {
        String csvFile = "data/aspat.csv";
        int lineCount = 0;
        int maxLines = 10000; // 限制读取行数用于测试

        System.out.println("开始测试 FPATreeV2 构建...");
        System.out.println("数据文件: " + csvFile);
        System.out.println("最大行数: " + maxLines);
        System.out.println();

        // 1. 构建 BitTrie
        BitTrie<String> trie = new BitTrie<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = br.readLine()) != null && lineCount < maxLines) {
                lineCount++;
                if (lineCount % 1000 == 0) {
                    System.out.println("已处理 " + lineCount + " 行...");
                }

                // 解析 CSV: 前缀/长度,AS路径
                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;

                // 解析 IP 前缀
                IPAddress prefix;
                try {
                    prefix = new IPAddressString(parts[0].trim()).toAddress();
                } catch (AddressStringException e) {
                    continue; // 跳过无效的 IP 地址
                }
                // 获取字节数组：IPv4 为 4 字节
                byte[] bytes;
                if (prefix.isIPv4()) {
                    bytes = prefix.toIPv4().getBytes();
                } else {
                    bytes = prefix.toIPv6().getBytes();
                }
                int prefixLength = prefix.getNetworkPrefixLength();

                // AS 路径作为值
                String asPath = parts[1].trim();

                // 插入 Trie
                trie.put(bytes, prefixLength, asPath);
            }
        } catch (IOException e) {
            System.err.println("读取文件失败: " + e.getMessage());
            return;
        }

        System.out.println();
        System.out.println("=== BitTrie 构建完成 ===");
        System.out.println("总前缀数: " + lineCount);

        // 2. 转换为 ForwardingPortArray
        long startTime = System.currentTimeMillis();
        ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(trie);
        long convertTime = System.currentTimeMillis() - startTime;

        System.out.println("ForwardingPortArray 转换耗时: " + convertTime + " ms");

        // 3. 构建 FPATreeV2
        startTime = System.currentTimeMillis();
        FPATreeV2<String> tree = FPATreeV2.build(fpa);
        long buildTime = System.currentTimeMillis() - startTime;

        System.out.println();
        System.out.println("=== FPATreeV2 构建完成 ===");
        System.out.println("构建耗时: " + buildTime + " ms");

        // 4. 输出统计信息
        System.out.println();
        System.out.println("=== 统计信息 ===");

        // 统计 rootChunk 类型分布
        int leafCount = 0;
        int denseCount = 0;
        int nullCount = 0;

        for (int i = 0; i < tree.rootChunk.length; i++) {
            for (int j = 0; j < tree.rootChunk[i].length; j++) {
                int entry = tree.rootChunk[i][j];
                if (entry == 0) {
                    nullCount++;
                } else {
                    int type = entry >>> 30;
                    if (type == TYPE_LEAF) {
                        leafCount++;
                    } else if (type == TYPE_DENSE) {
                        denseCount++;
                    }
                }
            }
        }

        System.out.println("Layer 1 (rootChunk):");
        System.out.println("  - LEAF 类型: " + leafCount);
        System.out.println("  - DENSE 类型: " + denseCount);
        System.out.println("  - 空值 (0): " + nullCount);
        System.out.println("  - 总条目: " + (tree.rootChunk.length * tree.rootChunk[0].length));

        System.out.println();
        System.out.println("Layer 2/3 (chunkList):");
        System.out.println("  - Chunk 数量: " + tree.chunkList.size());

        int totalLookupEntries = 0;
        for (ChunkEntry[] group : tree.chunkList) {
            for (ChunkEntry entry : group) {
                if (entry.lookupEntries != null) {
                    totalLookupEntries += entry.lookupEntries.length;
                }
            }
        }
        System.out.println("  - 总 lookupEntry 数: " + totalLookupEntries);

        System.out.println();
        System.out.println("resultList (去重值):");
        System.out.println("  - 唯一值数量: " + tree.resultList.size());

        // 5. 简单查询测试
        System.out.println();
        System.out.println("=== 查询测试 ===");

        // 测试几个已知的前缀
        String[] testPrefixes = {
            "1.0.0.0/24", "8.8.8.0/24", "192.168.0.0/16", "10.0.0.0/8"
        };

        for (String prefixStr : testPrefixes) {
            try {
                IPAddress prefix = new IPAddressString(prefixStr).toAddress();
                byte[] bytes;
                if (prefix.isIPv4()) {
                    bytes = prefix.toIPv4().getBytes();
                } else {
                    bytes = prefix.toIPv6().getBytes();
                }
                String result = trie.get(bytes);
                System.out.println(prefixStr + " -> " + (result != null ? result : "未找到"));
            } catch (AddressStringException e) {
                System.out.println(prefixStr + " -> 无效地址");
            }
        }

        System.out.println();
        System.out.println("=== 测试完成 ===");
    }
}
