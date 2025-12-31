package com.github.abjfh;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 测试 aspat.csv 数据加载 */
public class AsPatDataTest {

    @Test
    public void testLoadAndBuildFPATree() {
        String csvPath = "data/aspat.csv";
        BitTrie<String> trie = new BitTrie<>();

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(csvPath), StandardCharsets.UTF_8))) {

            String line;
            int count = 0;
            // 跳过标题行
            reader.readLine();

            long startTime = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 解析 CSV: prefix/length,as_path
                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;

                String[] prefixParts = parts[0].split("/");
                if (prefixParts.length != 2) continue;

                String prefix = prefixParts[0];
                int prefixLength = Integer.parseInt(prefixParts[1]);
                String asPath = parts[1];

                // 解析 IP 前缀
                byte[] prefixBytes = parseIpPrefix(prefix);
                if (prefixBytes != null) {
                    trie.put(prefixBytes, prefixLength, asPath);
                    count++;
                }

                if (count % 100000 == 0) {
                    System.out.println("已加载: " + count + " 条路由");
                }
            }
            long loadTime = System.currentTimeMillis() - startTime;
            System.out.println("加载完成: " + count + " 条路由, 耗时: " + loadTime + "ms");

            // 转换为 FPA
            startTime = System.currentTimeMillis();
            ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(trie);
            long convertTime = System.currentTimeMillis() - startTime;
            System.out.println("转换为 FPA 耗时: " + convertTime + "ms");

            // 构建 FPATree
            startTime = System.currentTimeMillis();
            FPATree<String> fpaTree = FPATree.build(fpa);
            long buildTime = System.currentTimeMillis() - startTime;
            System.out.println("构建 FPATree 耗时: " + buildTime + "ms");

            // 测试查找
            System.out.println("\n测试查找:");
            String[] testIps = {"8.8.8.8", "1.1.1.1", "93.184.216.34"};
            for (String testIp : testIps) {
                byte[] ipBytes = parseIpPrefix(testIp);
                String result = fpaTree.lookup(ipBytes);
                System.out.println(testIp + " -> " + result);
            }

            System.out.println("\n总耗时: " + (loadTime + convertTime + buildTime) + "ms");

        } catch (Exception e) {
            throw new RuntimeException("加载 CSV 数据失败: " + e.getMessage(), e);
        }
    }

    private byte[] parseIpPrefix(String prefix) {
        String[] octets = prefix.split("\\.");
        if (octets.length != 4) return null;

        byte[] result = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                result[i] = (byte) Integer.parseInt(octets[i]);
            }
            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
