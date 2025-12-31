package com.github.abjfh.benchmark;

import com.github.abjfh.BitTrie;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * BitTrie性能基准测试
 *
 * <p>测试场景：
 *
 * <ul>
 *   <li>插入性能 - 不同规模和分布的前缀
 *   <li>查找性能 - 随机IP、顺序IP、边界IP
 *   <li>最长前缀匹配性能
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
@Threads(Threads.MAX)
public class BitTrieBenchmark {
    public static void main(String[] args) throws RunnerException {
        Options opt =
                new OptionsBuilder()
                        .include(BitTrieBenchmark.class.getSimpleName())
                        .shouldDoGC(false)
                        .jvmArgsAppend("-Xmx4G", "-Xms4G")
                        .build();

        new Runner(opt).run();
    }

    // ==================== 测试数据生成 ====================

    @State(Scope.Benchmark)
    public static class LargeData {
        BitTrie<String[]> trie;
        byte[][] testIps;

        @Setup(Level.Trial)
        public void setup() {
            trie = new BitTrie<>();
            testIps = new byte[100000][4];

            Random random = new Random(42);
            // 模拟大型路由表
            for (int i = 0; i < 10000; i++) {
                int a = random.nextInt(256);
                int b = random.nextInt(256);
                int c = random.nextInt(256);
                byte[] prefixIp = new byte[] {(byte) a, (byte) b, (byte) c, 0};
                int prefixLen = 8 + random.nextInt(17);
                trie.put(
                        prefixIp,
                        prefixLen,
                        new String[] {a + "." + b + "." + c + ".0/" + prefixLen});
            }

            for (int i = 0; i < 100000; i++) {
                testIps[i][0] = (byte) random.nextInt(256);
                testIps[i][1] = (byte) random.nextInt(256);
                testIps[i][2] = (byte) random.nextInt(256);
                testIps[i][3] = (byte) random.nextInt(256);
            }
        }
    }

    // ==================== 查找性能测试 ====================

    @Benchmark
    public void lookupLargeData(LargeData data, Blackhole bh) {
        for (int i = 0; i < data.testIps.length; i++) {
            bh.consume(data.trie.get(data.testIps[i]));
        }
    }
}
