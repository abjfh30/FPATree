package com.github.abjfh;

import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressSeqRange;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Application {
    public static void main(String[] args) throws Exception {
        BitTrie<String[]> bitTrie = new BitTrie<>();
        List<byte[]> testIps = new ArrayList<>();
        buildTrie(bitTrie, testIps);

        // 使用转换器将 BitTrie 转换为分层 ForwardingPortArray
        // 深度配置: 16 + 8 + 8 = 32 位
        ForwardingPortArray<String[]> result = TrieToFPAConverter.convert(bitTrie);
        FPATree<String[]> tree = FPATree.build(result);

        System.out.println("转换完成");
        System.out.println("compressedChunkList大小: " + tree.compressedChunkList.size());
        System.out.println("sparseChunkList大小: " + tree.sparseChunkList.size());
        System.out.println("resultList大小: " + tree.resultList.size());

        // 验证查找功能
        validateLookup(bitTrie, tree, testIps);
    }

    private static void validateLookup(
            BitTrie<String[]> bitTrie, FPATree<String[]> tree, List<byte[]> testIps) {
        System.out.println("\n开始验证查找功能...");

        int passCount = 0;
        int failCount = 0;
        int testCount = testIps.size();

        for (byte[] ip : testIps) {
            String[] trieResult = bitTrie.get(ip);
            String[] treeResult = tree.lookup(ip);

            // 比较
            boolean match = compareResults(trieResult, treeResult);
            if (match) {
                passCount++;
            } else {
                failCount++;
                String ipStr = bytesToIp(ip);
                System.out.printf(
                        "不匹配: IP=%s, Trie=%s, Tree=%s%n",
                        ipStr,
                        trieResult == null ? "null" : "\"" + trieResult[0] + "\"",
                        treeResult == null ? "null" : "\"" + treeResult[0] + "\"");
            }
        }

        System.out.printf("\n验证完成: 通过=%d, 失败=%d, 总计=%d%n", passCount, failCount, testCount);

        if (failCount == 0) {
            System.out.println("✓ 所有测试通过！");
        }
    }

    private static boolean compareResults(String[] r1, String[] r2) {
        if (r1 == null && r2 == null) return true;
        if (r1 == null || r2 == null) return false;
        if (r1.length != r2.length) return false;
        for (int i = 0; i < r1.length; i++) {
            if (!r1[i].equals(r2[i])) return false;
        }
        return true;
    }

    private static String bytesToIp(byte[] bytes) {
        return String.format(
                "%d.%d.%d.%d", bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF);
    }

    private static void buildTrie(BitTrie<String[]> bitTrie, List<byte[]> testIps)
            throws Exception {
        try (BufferedReader br = Files.newBufferedReader(Paths.get("data/BGP_INFO_IPV4.csv.bak"))) {
            String line;
            Random random = new Random(42); // 固定种子保证可重复
            int lineCount = 0;

            while ((line = br.readLine()) != null) {
                String[] row = line.split(",");
                IPv4Address[] withPrefixBlocks =
                        new IPv4AddressSeqRange(
                                        new IPAddressString(row[0]).toAddress().toIPv4(),
                                        new IPAddressString(row[1]).toAddress().toIPv4())
                                .spanWithPrefixBlocks();
                for (IPv4Address address : withPrefixBlocks) {
                    bitTrie.put(address.getBytes(), address.getPrefixLength(), row);

                    // 随机收集测试IP（每100条收集1个）
                    // 生成该前缀范围内的随机IP
                    byte[] ipBytes = address.getBytes();
                    int prefixLen = address.getPrefixLength();
                    byte[] randomIp = generateRandomIpInPrefix(ipBytes, prefixLen, random);
                    testIps.add(randomIp);
                }
                lineCount++;
                if (lineCount % 1000 == 0) {
                    System.out.println("已处理 " + lineCount + " 行...");
                }
            }
        }
        System.out.println("Trie构建完成，收集了 " + testIps.size() + " 个测试IP");
    }

    private static byte[] generateRandomIpInPrefix(
            byte[] prefixBytes, int prefixLen, Random random) {
        byte[] ip = prefixBytes.clone();

        // 只修改前缀长度之后的部分
        for (int i = prefixLen; i < 32; i++) {
            int byteIdx = i / 8;
            int bitIdx = 7 - (i % 8);
            if (random.nextBoolean()) {
                ip[byteIdx] |= (byte) (1 << bitIdx);
            } else {
                ip[byteIdx] &= (byte) ~(1 << bitIdx);
            }
        }

        return ip;
    }
}
