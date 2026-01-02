package com.github.abjfh;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试 BitTrie、ForwardingPortArray 和 FPATreeV2 的查询结果一致性
 */
public class Application {

    public static void main(String[] args) {
        String csvPath = "data/aspat.csv";

        System.out.println("正在加载数据...");
        List<PrefixEntry> entries = loadAsPatCsv(csvPath);
        System.out.println("已加载 " + entries.size() + " 条前缀记录");

        // 构建 BitTrie
        System.out.println("\n正在构建 BitTrie...");
        BitTrie<String> bitTrie = new BitTrie<>();
        for (PrefixEntry entry : entries) {
            bitTrie.put(entry.ipBytes, entry.prefixLength, entry.asPath);
        }
        System.out.println("BitTrie 构建完成");

        // 转换为 ForwardingPortArray
        System.out.println("\n正在转换为 ForwardingPortArray...");
        ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(bitTrie);
        System.out.println("ForwardingPortArray 转换完成");

        // 构建 FPATreeV2
        System.out.println("\n正在构建 FPATreeV2...");
        FPATreeV2<String> fpaTree = FPATreeV2.build(fpa);
        System.out.println("FPATreeV2 构建完成");

        // 对比查询结果
        System.out.println("\n开始对比查询结果...");
        compareResults(entries, bitTrie, fpa, fpaTree);
    }

    /**
     * 加载 aspat.csv 文件
     */
    private static List<PrefixEntry> loadAsPatCsv(String path) {
        List<PrefixEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 格式: 1.0.0.0/24,2497 13335
                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    System.err.println("格式错误，跳过: " + line);
                    continue;
                }

                String prefix = parts[0].trim();
                String asPath = parts[1].trim();

                // 解析前缀
                String[] prefixParts = prefix.split("/");
                if (prefixParts.length != 2) {
                    System.err.println("前缀格式错误，跳过: " + line);
                    continue;
                }

                String ipStr = prefixParts[0];
                int prefixLength = Integer.parseInt(prefixParts[1]);

                byte[] ipBytes = ipToBytes(ipStr);

                entries.add(new PrefixEntry(ipBytes, prefixLength, asPath));
            }

        } catch (IOException e) {
            System.err.println("读取文件失败: " + e.getMessage());
            System.exit(1);
        }

        return entries;
    }

    /**
     * 将 IPv4 地址字符串转换为字节数组
     */
    private static byte[] ipToBytes(String ipStr) {
        String[] octets = ipStr.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("无效的 IP 地址: " + ipStr);
        }

        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(octets[i]);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("无效的 IP 地址: " + ipStr);
            }
            bytes[i] = (byte) value;
        }

        return bytes;
    }

    /**
     * 对比 BitTrie、ForwardingPortArray 和 FPATreeV2 的查询结果
     */
    private static void compareResults(List<PrefixEntry> entries,
                                       BitTrie<String> bitTrie,
                                       ForwardingPortArray<String> fpa,
                                       FPATreeV2<String> fpaTree) {
        int matchCount = 0;
        int mismatchCount = 0;
        int totalCount = 0;
        int maxPrint = 20;
        int printed = 0;

        // 对每个前缀进行查询
        System.out.println("测试前缀地址...");
        for (PrefixEntry entry : entries) {
            String bitTrieResult = bitTrie.get(entry.ipBytes);
            String fpaResult = fpa.search(entry.ipBytes);
            String fpaTreeResult = fpaTree.search(entry.ipBytes);

            totalCount++;

            if (resultsMatch(bitTrieResult, fpaResult, fpaTreeResult)) {
                matchCount++;
            } else {
                mismatchCount++;
                if (printed++ < maxPrint) {
                    String ipStr = bytesToIp(entry.ipBytes);
                    System.out.printf(
                            "不匹配: %s/%d | BitTrie: %s | FPA: %s | FPATreeV2: %s%n",
                            ipStr, entry.prefixLength, bitTrieResult, fpaResult, fpaTreeResult);
                }
            }
        }

        // 额外测试：测试每个前缀覆盖的地址空间
        System.out.println("测试前缀覆盖的地址空间...");
        List<IpTestEntry> testIps = generateTestIps(entries);
        printed = 0;

        for (IpTestEntry testEntry : testIps) {
            String bitTrieResult = bitTrie.get(testEntry.ipBytes);
            String fpaResult = fpa.search(testEntry.ipBytes);
            String fpaTreeResult = fpaTree.search(testEntry.ipBytes);

            totalCount++;

            if (resultsMatch(bitTrieResult, fpaResult, fpaTreeResult)) {
                matchCount++;
            } else {
                mismatchCount++;
                if (printed++ < maxPrint) {
                    String ipStr = bytesToIp(testEntry.ipBytes);
                    System.out.printf("不匹配: %s | BitTrie: %s | FPA: %s | FPATreeV2: %s%n",
                            ipStr, bitTrieResult, fpaResult, fpaTreeResult);
                }
            }
        }

        // 输出统计结果
        System.out.println("\n========================================");
        System.out.println("测试统计:");
        System.out.println("总测试数: " + totalCount);
        System.out.println("匹配数: " + matchCount);
        System.out.println("不匹配数: " + mismatchCount);
        double matchRate = (double) matchCount / totalCount * 100;
        System.out.printf("匹配率: %.2f%%%n", matchRate);
        System.out.println("========================================");

        if (mismatchCount > 0) {
            System.exit(1);
        }
    }

    /**
     * 检查三个结果是否一致
     */
    private static boolean resultsMatch(String r1, String r2, String r3) {
        if (r1 == null && r2 == null && r3 == null) {
            return true;
        }
        if (r1 != null && r1.equals(r2) && r1.equals(r3)) {
            return true;
        }
        return false;
    }

    /**
     * 生成测试 IP 地址
     */
    private static List<IpTestEntry> generateTestIps(List<PrefixEntry> entries) {
        List<IpTestEntry> testIps = new ArrayList<>();

        // 为每个前缀生成测试地址（前缀地址、中间地址、广播地址）
        for (PrefixEntry entry : entries) {
            // 前缀地址
            testIps.add(new IpTestEntry(entry.ipBytes.clone()));

            // 中间地址（如果前缀长度允许）
            if (entry.prefixLength < 31) {
                byte[] midIp = entry.ipBytes.clone();
                int hostBitCount = 32 - entry.prefixLength;
                if (hostBitCount >= 8) {
                    midIp[3] = (byte) 128; // 设置第4个字节的中间值
                } else {
                    int shift = 8 - hostBitCount;
                    midIp[3] = (byte) ((1 << (hostBitCount - 1)) << shift);
                }
                testIps.add(new IpTestEntry(midIp));
            }
        }

        return testIps;
    }

    /**
     * 将字节数组转换为 IP 地址字符串
     */
    private static String bytesToIp(byte[] bytes) {
        return String.format("%d.%d.%d.%d",
                bytes[0] & 0xFF,
                bytes[1] & 0xFF,
                bytes[2] & 0xFF,
                bytes[3] & 0xFF);
    }

    /**
     * 前缀条目
     */
    static class PrefixEntry {
        byte[] ipBytes;
        int prefixLength;
        String asPath;

        PrefixEntry(byte[] ipBytes, int prefixLength, String asPath) {
            this.ipBytes = ipBytes;
            this.prefixLength = prefixLength;
            this.asPath = asPath;
        }
    }

    /**
     * IP 测试条目
     */
    static class IpTestEntry {
        byte[] ipBytes;

        IpTestEntry(byte[] ipBytes) {
            this.ipBytes = ipBytes;
        }
    }
}
