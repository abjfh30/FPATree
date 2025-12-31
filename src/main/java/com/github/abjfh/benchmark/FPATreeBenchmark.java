package com.github.abjfh.benchmark;

import com.github.abjfh.BitTrie;
import com.github.abjfh.FPATree;
import com.github.abjfh.ForwardingPortArray;
import com.github.abjfh.TrieToFPAConverter;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * FPATree性能基准测试
 *
 * <p>测试场景：
 *
 * <ul>
 *   <li>构建性能 - 从BitTrie转换为FPATree
 *   <li>查找性能 - 字节数组版本 vs int版本
 *   <li>不同规模数据集的性能
 *   <li>压缩结构 vs 稀疏结构的性能
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
@Threads(Threads.MAX)
public class FPATreeBenchmark {
    public static void main(String[] args) throws RunnerException {
        Options opt =
                new OptionsBuilder()
                        .include(com.github.abjfh.benchmark.FPATreeBenchmark.class.getSimpleName())
                        .shouldDoGC(false)
                        .jvmArgsAppend("-Xmx4G", "-Xms4G")
                        .resultFormat(ResultFormatType.JSON)
                        .build();

        new Runner(opt).run();
    }

    // ==================== 真实AS路径数据测试 (aspat.csv) ====================

    @State(Scope.Benchmark)
    public static class RealASData {
        FPATree<String> fpaTree;
        byte[][] testIps;
        int[] testIpsAsInt;
        int entryCount;

        @Setup(Level.Trial)
        public void setup() {
            String csvPath = "data/aspat.csv";
            BitTrie<String> trie = new BitTrie<>();

            try (BufferedReader reader = Files.newBufferedReader(Paths.get(csvPath))) {

                String line;
                int count = 0;
                // 跳过标题行
                reader.readLine();

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
                }
                entryCount = count;

            } catch (Exception e) {
                throw new RuntimeException("加载 CSV 数据失败: " + e.getMessage(), e);
            }

            // 构建 FPATree
            ForwardingPortArray<String> fpa = TrieToFPAConverter.convert(trie);
            fpaTree = FPATree.build(fpa);

            // 生成测试 IP（从真实数据中随机选择）
            Random random = new Random(42);
            int testSize = 100000;
            testIps = new byte[testSize][4];
            testIpsAsInt = new int[testSize];

            for (int i = 0; i < testSize; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);

                int ip = 0;
                for (int j = 0; j < 4; j++) {
                    ip = (ip << 8) | (testIps[i][j] & 0xFF);
                }
                testIpsAsInt[i] = ip;
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

    @Benchmark
    public void lookupByteArrayRealData(RealASData data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.fpaTree.lookup(data.testIps[i]));
        }
    }

    @Benchmark
    public void lookupIntRealData(RealASData data, Blackhole bh) {
        for (int i = 0; i < data.testIpsAsInt.length; i++) {
            bh.consume(data.fpaTree.lookup(data.testIpsAsInt[i]));
        }
    }
}
