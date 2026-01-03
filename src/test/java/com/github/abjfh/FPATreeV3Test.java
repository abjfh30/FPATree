package com.github.abjfh;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FPATreeV3 正确性测试。
 *
 * <p>对比 FPATreeV2 和 FPATreeV3 的查询结果，确保 V3 与 V2 行为一致。
 */
public class FPATreeV3Test {

    public static void main(String[] args) {
        testSimpleData();
        testRealWorldData();
        System.out.println("\n=== 所有测试通过! ===");
    }

    /**
     * 测试简单数据集。
     */
    public static void testSimpleData() {
        System.out.println("\n=== 测试: testSimpleData ===");

        BitTrie<String> bitTrie = new BitTrie<>();

        // 添加一些简单的 IP 前缀
        addPrefix(bitTrie, "192.168.0.0", 16, "V1");
        addPrefix(bitTrie, "192.168.1.0", 24, "V2");
        addPrefix(bitTrie, "10.0.0.0", 8, "V3");
        addPrefix(bitTrie, "172.16.0.0", 12, "V4");

        bitTrie.compress();

        ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(bitTrie);

        FPATreeV2<String> v2 = FPATreeV2.build(fpa);
        FPATreeV3<String> v3 = FPATreeV3.build(fpa);

        // 测试查询
        String[] testIps = {
            "192.168.0.1",    // 应该匹配 V1
            "192.168.1.1",    // 应该匹配 V2
            "10.0.0.1",       // 应该匹配 V3
            "172.16.0.1",     // 应该匹配 V4
            "8.8.8.8",        // 应该匹配 null
            "192.168.2.1",    // 应该匹配 V1
        };

        for (String ipStr : testIps) {
            byte[] ipBytes = ipToBytes(ipStr);
            String v2Result = v2.search(ipBytes);
            String v3Result = v3.search(ipBytes);

            assertEqual(v2Result, v3Result,
                "查询 " + ipStr + " 时 V2 和 V3 结果不一致 (V2=" + v2Result + ", V3=" + v3Result + ")");
        }

        System.out.println("✓ 测试通过");
    }

    /**
     * 测试真实世界数据。
     */
    public static void testRealWorldData() {
        System.out.println("\n=== 测试: testRealWorldData ===");

        String csvPath = "data/aspat.csv";

        System.out.println("正在加载数据...");
        List<PrefixEntry> entries = loadAsPatCsv(csvPath);
        System.out.println("已加载 " + entries.size() + " 条前缀记录");

        // 构建 BitTrie
        System.out.println("正在构建 BitTrie...");
        BitTrie<String> bitTrie = new BitTrie<>();
        for (PrefixEntry entry : entries) {
            bitTrie.put(entry.ipBytes, entry.prefixLength, entry.asPath);
        }
        bitTrie.compress();
        System.out.println("BitTrie 构建完成");

        // 转换为 ForwardingPortArray
        System.out.println("正在转换为 ForwardingPortArray...");
        ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(bitTrie);
        System.out.println("ForwardingPortArray 转换完成");

        // 构建 V2 和 V3
        System.out.println("正在构建 FPATreeV2...");
        FPATreeV2<String> v2 = FPATreeV2.build(fpa);
        System.out.println("FPATreeV2 构建完成");

        System.out.println("正在构建 FPATreeV3...");
        FPATreeV3<String> v3 = FPATreeV3.build(fpa);
        System.out.println("FPATreeV3 构建完成");

        // 测试前缀地址
        System.out.println("\n测试前缀地址...");
        int matchCount = 0;
        int mismatchCount = 0;
        int maxPrint = 10;
        int printed = 0;

        for (PrefixEntry entry : entries) {
            String v2Result = v2.search(entry.ipBytes);
            String v3Result = v3.search(entry.ipBytes);

            if (java.util.Objects.equals(v2Result, v3Result)) {
                matchCount++;
            } else {
                mismatchCount++;
                if (printed++ < maxPrint) {
                    String ipStr = bytesToIp(entry.ipBytes);
                    System.out.printf("不匹配: %s/%d | V2: %s | V3: %s%n",
                        ipStr, entry.prefixLength, v2Result, v3Result);
                }
            }
        }

        // 额外测试：生成测试 IP
        System.out.println("测试前缀覆盖的地址空间...");
        List<IpTestEntry> testIps = generateTestIps(entries);
        printed = 0;

        for (IpTestEntry testEntry : testIps) {
            String v2Result = v2.search(testEntry.ipBytes);
            String v3Result = v3.search(testEntry.ipBytes);

            if (java.util.Objects.equals(v2Result, v3Result)) {
                matchCount++;
            } else {
                mismatchCount++;
                if (printed++ < maxPrint) {
                    String ipStr = bytesToIp(testEntry.ipBytes);
                    System.out.printf("不匹配: %s | V2: %s | V3: %s%n",
                        ipStr, v2Result, v3Result);
                }
            }
        }

        // 输出统计结果
        System.out.println("\n========================================");
        System.out.println("测试统计:");
        System.out.println("总测试数: " + (matchCount + mismatchCount));
        System.out.println("匹配数: " + matchCount);
        System.out.println("不匹配数: " + mismatchCount);
        double matchRate = (double) matchCount / (matchCount + mismatchCount) * 100;
        System.out.printf("匹配率: %.2f%%%n", matchRate);
        System.out.println("========================================");

        if (mismatchCount > 0) {
            throw new AssertionError("存在不匹配的查询结果!");
        }

        System.out.println("✓ 测试通过");
    }

    /**
     * 辅助方法：添加前缀。
     */
    private static void addPrefix(BitTrie<String> bitTrie, String ipStr, int prefixLength, String value) {
        byte[] ipBytes = ipToBytes(ipStr);
        bitTrie.put(ipBytes, prefixLength, value);
    }

    /**
     * 加载 aspat.csv 文件。
     */
    private static List<PrefixEntry> loadAsPatCsv(String path) {
        List<PrefixEntry> entries = new ArrayList<>();

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    continue;
                }

                String prefix = parts[0].trim();
                String asPath = parts[1].trim();

                String[] prefixParts = prefix.split("/");
                if (prefixParts.length != 2) {
                    continue;
                }

                String ipStr = prefixParts[0];
                int prefixLength = Integer.parseInt(prefixParts[1]);

                byte[] ipBytes = ipToBytes(ipStr);
                entries.add(new PrefixEntry(ipBytes, prefixLength, asPath));
            }

        } catch (IOException e) {
            throw new RuntimeException("读取文件失败", e);
        }

        return entries;
    }

    /**
     * 生成测试 IP 地址。
     */
    private static List<IpTestEntry> generateTestIps(List<PrefixEntry> entries) {
        List<IpTestEntry> testIps = new ArrayList<>();

        for (PrefixEntry entry : entries) {
            testIps.add(new IpTestEntry(entry.ipBytes.clone()));

            if (entry.prefixLength < 31) {
                byte[] midIp = entry.ipBytes.clone();
                int hostBitCount = 32 - entry.prefixLength;
                if (hostBitCount >= 8) {
                    midIp[3] = (byte) 128;
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
     * 将 IPv4 地址字符串转换为字节数组。
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
     * 将字节数组转换为 IP 地址字符串。
     */
    private static String bytesToIp(byte[] bytes) {
        return String.format(
                "%d.%d.%d.%d", bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF);
    }

    private static void assertEqual(String expected, String actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

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

    static class IpTestEntry {
        byte[] ipBytes;

        IpTestEntry(byte[] ipBytes) {
            this.ipBytes = ipBytes;
        }
    }
}
