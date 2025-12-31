package com.github.abjfh.benchmark;

import com.github.abjfh.BitTrie;
import com.github.abjfh.FPATree;
import com.github.abjfh.ForwardingPortArray;
import com.github.abjfh.TrieToFPAConverter;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

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
@OutputTimeUnit(TimeUnit.SECONDS)
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
                        .build();

        new Runner(opt).run();
    }

    // ==================== 测试数据生成 ====================

    @State(Scope.Benchmark)
    public static class LargeData {
        FPATree<String[]> fpaTree;
        byte[][] testIps;
        int[] testIpsAsInt;

        @Setup(Level.Trial)
        public void setup() {
            BitTrie<String[]> trie = new BitTrie<>();
            testIps = new byte[100000][4];
            testIpsAsInt = new int[100000];

            Random random = new Random(42);
            for (int i = 0; i < 10000; i++) {
                int a = random.nextInt(256);
                int b = random.nextInt(256);
                int c = random.nextInt(256);
                byte[] prefixIp = new byte[] {(byte) a, (byte) b, (byte) c, 0};
                trie.put(prefixIp, 24, new String[] {a + "." + b + "." + c + ".0/24", "AS" + i});
            }

            ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
            fpaTree = FPATree.build(fpa);

            for (int i = 0; i < 100000; i++) {
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
    }

    // ==================== 构建性能测试 ====================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void buildFPATreeLarge() {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int c = random.nextInt(256);
            byte[] prefixIp = new byte[] {(byte) a, (byte) b, (byte) c, 0};
            trie.put(prefixIp, 24, new String[] {a + "." + b + "." + c + ".0/24", "AS" + i});
        }
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree.build(fpa);
    }

    // ==================== 查找性能测试 - 字节数组版本 ====================

    @Benchmark
    public void lookupByteArrayLarge(LargeData data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.fpaTree.lookup(data.testIps[i]));
        }
    }

    // ==================== 压缩结构性能测试 ====================

    @State(Scope.Benchmark)
    public static class CompressedData {
        FPATree<String[]> fpaTree;
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            BitTrie<String[]> trie = new BitTrie<>();
            testIps = new byte[1000][4];

            Random random = new Random(42);
            // 创建密集的Layer 2/3结构
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    byte[] prefixIp = new byte[] {(byte) (10 + a), (byte) b, 0, 0};
                    trie.put(
                            prefixIp,
                            24,
                            new String[] {(10 + a) + "." + b + ".0.0/24", "AS" + (a * 16 + b)});
                }
            }

            ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
            fpaTree = FPATree.build(fpa);

            // 生成测试IP（在相同范围内）
            for (int i = 0; i < 1000; i++) {
                testIps[i][0] = (byte) (10 + random.nextInt(16));
                testIps[i][1] = (byte) random.nextInt(16);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    @Benchmark
    public void lookupCompressedStructure(CompressedData data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.fpaTree.lookup(data.testIps[i]));
        }
    }

    // ==================== 稀疏结构性能测试 ====================

    @State(Scope.Benchmark)
    public static class SparseData {
        FPATree<String[]> fpaTree;
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            BitTrie<String[]> trie = new BitTrie<>();
            testIps = new byte[1000][4];

            // 创建稀疏结构
            trie.put(new byte[] {(byte) 10, 0, 0, 0}, 16, new String[] {"10.0.0.0/16", "default"});
            trie.put(new byte[] {(byte) 10, 0, 1, 0}, 24, new String[] {"10.0.1.0/24", "specific"});
            trie.put(new byte[] {(byte) 10, 0, 2, 0}, 24, new String[] {"10.0.2.0/24", "specific"});

            ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
            fpaTree = FPATree.build(fpa);

            Random random = new Random(42);
            for (int i = 0; i < 1000; i++) {
                testIps[i][0] = (byte) 10;
                testIps[i][1] = 0;
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    @Benchmark
    public void lookupSparseStructure(SparseData data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.fpaTree.lookup(data.testIps[i]));
        }
    }
}
