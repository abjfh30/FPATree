package com.github.abjfh.jmh;

import com.github.abjfh.*;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** FPATreeV2 的 JMH 基准测试 测试指标：吞吐量 (ops/ms) */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Threads(Threads.MAX)
public class FPATreeV2Benchmark {
    public static void main(String[] args) throws RunnerException {
        Options opt =
                new OptionsBuilder()
                        .include(FPATreeV2Benchmark.class.getSimpleName())
                        .resultFormat(ResultFormatType.JSON)
                        .build();

        new Runner(opt).run();
    }

    private FPATreeV2<String> fpaTree;
    private List<byte[]> testIps;

    @Setup(Level.Trial)
    public void setup() {
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
        System.out.println("BitTrie 构建完成");

        // 转换为 ForwardingPortArray
        System.out.println("正在转换为 ForwardingPortArray...");
        ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(bitTrie);
        System.out.println("ForwardingPortArray 转换完成");

        // 构建 FPATreeV2
        System.out.println("正在构建 FPATreeV2...");
        fpaTree = FPATreeV2.build(fpa);
        System.out.println("FPATreeV2 构建完成");

        // 生成测试 IP 地址
        System.out.println("正在生成测试 IP...");
        testIps = generateTestIps(entries);
        System.out.println("生成 " + testIps.size() + " 个测试 IP");
    }

    Random random = new Random(2);
    byte[] ip;

    @Setup(Level.Invocation)
    public void getIp() {
        ip = testIps.get(random.nextInt(testIps.size()));
    }

    @Benchmark
    public void benchmarkSearch(Blackhole bh) {
        String result = fpaTree.search(ip);
        bh.consume(result);
    }

    /** 加载 aspat.csv 文件 */
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

                // 格式: 1.0.0.0/24,2497 13335
                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    continue;
                }

                String prefix = parts[0].trim();
                String asPath = parts[1].trim();

                // 解析前缀
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

    /** 将 IPv4 地址字符串转换为字节数组 */
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

    /** 生成测试 IP 地址 */
    private static List<byte[]> generateTestIps(List<PrefixEntry> entries) {
        List<byte[]> testIps = new ArrayList<>();

        // 为每个前缀生成多个测试地址
        for (PrefixEntry entry : entries) {
            // 前缀地址
            testIps.add(entry.ipBytes.clone());

            // 中间地址（如果前缀长度允许）
            if (entry.prefixLength < 31) {
                byte[] midIp = entry.ipBytes.clone();
                int hostBitCount = 32 - entry.prefixLength;
                if (hostBitCount >= 8) {
                    midIp[3] = (byte) 128;
                } else {
                    int shift = 8 - hostBitCount;
                    midIp[3] = (byte) ((1 << (hostBitCount - 1)) << shift);
                }
                testIps.add(midIp);
            }
        }

        return testIps;
    }

    /** 前缀条目 */
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
}
