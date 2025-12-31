package com.github.abjfh.benchmark;

import com.github.abjfh.BitTrie;
import com.github.abjfh.FPATree;
import com.github.abjfh.ForwardingPortArray;
import com.github.abjfh.TrieToFPAConverter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 端到端性能基准测试
 *
 * <p>比较BitTrie和FPATree的性能差异，测试完整流程：
 * <ul>
 *   <li>构建 + 查找总时间
 *   <li>内存使用（通过统计信息推断）
 *   <li>不同工作负载下的性能对比
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class EndToEndBenchmark {

    // ==================== 小规模数据集 ====================

    @State(Scope.Benchmark)
    public static class SmallDataSet {
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            testIps = new byte[1000][4];
            Random random = new Random(42);
            for (int i = 0; i < 1000; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    @Benchmark
    public void bitTrieBuildAndLookupSmall(SmallDataSet data, Blackhole bh) {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);

        // 构建
        for (int i = 0; i < 100; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, 0, 0};
            trie.put(prefixIp, 16, new String[]{a + "." + b + ".0.0/16", "AS" + i});
        }

        // 查找
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(trie.get(data.testIps[i]));
        }
    }

    @Benchmark
    public void fpaTreeBuildAndLookupSmall(SmallDataSet data, Blackhole bh) {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);

        // 构建
        for (int i = 0; i < 100; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, 0, 0};
            trie.put(prefixIp, 16, new String[]{a + "." + b + ".0.0/16", "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        // 查找
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(tree.lookup(data.testIps[i]));
        }
    }

    // ==================== 中等规模数据集 ====================

    @State(Scope.Benchmark)
    public static class MediumDataSet {
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            testIps = new byte[10000][4];
            Random random = new Random(42);
            for (int i = 0; i < 10000; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    @Benchmark
    public void bitTrieBuildAndLookupMedium(MediumDataSet data, Blackhole bh) {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);

        // 构建
        for (int i = 0; i < 1000; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int c = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
            trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
        }

        // 查找
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(trie.get(data.testIps[i]));
        }
    }

    @Benchmark
    public void fpaTreeBuildAndLookupMedium(MediumDataSet data, Blackhole bh) {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);

        // 构建
        for (int i = 0; i < 1000; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int c = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
            trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        // 查找
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(tree.lookup(data.testIps[i]));
        }
    }

    // ==================== 大规模数据集 ====================

    @State(Scope.Benchmark)
    public static class LargeDataSet {
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            testIps = new byte[100000][4];
            Random random = new Random(42);
            for (int i = 0; i < 100000; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    @Benchmark
    public void bitTrieBuildAndLookupLarge(LargeDataSet data, Blackhole bh) {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);

        // 构建
        for (int i = 0; i < 10000; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int c = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
            trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
        }

        // 查找
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(trie.get(data.testIps[i]));
        }
    }

    @Benchmark
    public void fpaTreeBuildAndLookupLarge(LargeDataSet data, Blackhole bh) {
        BitTrie<String[]> trie = new BitTrie<>();
        Random random = new Random(42);

        // 构建
        for (int i = 0; i < 10000; i++) {
            int a = random.nextInt(256);
            int b = random.nextInt(256);
            int c = random.nextInt(256);
            byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
            trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
        }

        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
        FPATree<String[]> tree = FPATree.build(fpa);

        // 查找
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(tree.lookup(data.testIps[i]));
        }
    }

    // ==================== 仅查找性能对比 ====================

    @State(Scope.Benchmark)
    public static class LookupComparison {
        BitTrie<String[]> trie;
        FPATree<String[]> tree;
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            trie = new BitTrie<>();
            testIps = new byte[10000][4];

            Random random = new Random(42);
            for (int i = 0; i < 1000; i++) {
                int a = random.nextInt(256);
                int b = random.nextInt(256);
                int c = random.nextInt(256);
                byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
                trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
            }

            for (int i = 0; i < 10000; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }

            ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(trie);
            tree = FPATree.build(fpa);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void bitTrieLookupOnly(LookupComparison data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.trie.get(data.testIps[i]));
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void fpaTreeLookupOnly(LookupComparison data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.tree.lookup(data.testIps[i]));
        }
    }

    // ==================== 吞吐量对比 ====================

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int bitTrieThroughput(LookupComparison data) {
        int idx = (int) (System.nanoTime() % data.testIps.length);
        return data.trie.get(data.testIps[Math.abs(idx)]) == null ? 0 : 1;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int fpaTreeThroughput(LookupComparison data) {
        int idx = (int) (System.nanoTime() % data.testIps.length);
        return data.tree.lookup(data.testIps[Math.abs(idx)]) == null ? 0 : 1;
    }

    // ==================== 内存效率对比 ====================

    @State(Scope.Benchmark)
    public static class MemoryComparison {
        BitTrie<String[]> trie;

        @Setup(Level.Trial)
        public void setup() {
            trie = new BitTrie<>();
            Random random = new Random(42);
            for (int i = 0; i < 5000; i++) {
                int a = random.nextInt(256);
                int b = random.nextInt(256);
                int c = random.nextInt(256);
                byte[] prefixIp = new byte[]{(byte) a, (byte) b, (byte) c, 0};
                trie.put(prefixIp, 24, new String[]{a + "." + b + "." + c + ".0/24", "AS" + i});
            }
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public FPATree<String[]> buildFPATreeFromTrie(MemoryComparison data) {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(data.trie);
        return FPATree.build(fpa);
    }

    // ==================== 不同K值影响 ====================

    @State(Scope.Benchmark)
    public static class KValueComparison {
        BitTrie<String[]> trie;
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            trie = new BitTrie<>();
            testIps = new byte[1000][4];

            Random random = new Random(42);
            for (int i = 0; i < 100; i++) {
                int a = random.nextInt(256);
                int b = random.nextInt(256);
                byte[] prefixIp = new byte[]{(byte) a, (byte) b, 0, 0};
                trie.put(prefixIp, 16, new String[]{a + "." + b + ".0.0/16", "AS" + i});
            }

            for (int i = 0; i < 1000; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    @Benchmark
    public void buildWithK1(KValueComparison data) {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(data.trie);
        FPATree.build(fpa, 1);
    }

    @Benchmark
    public void buildWithK3(KValueComparison data) {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(data.trie);
        FPATree.build(fpa, 3);
    }

    @Benchmark
    public void buildWithK5(KValueComparison data) {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(data.trie);
        FPATree.build(fpa, 5);
    }

    @Benchmark
    public void buildWithK10(KValueComparison data) {
        ForwardingPortArray<String[]> fpa = TrieToFPAConverter.convert(data.trie);
        FPATree.build(fpa, 10);
    }
}
