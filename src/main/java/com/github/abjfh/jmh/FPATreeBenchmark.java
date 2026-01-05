package com.github.abjfh.jmh;

import com.github.abjfh.domain.IpSegment;
import com.github.abjfh.fpa.impl.BitTrie;
import com.github.abjfh.fpa.impl.FPATree;
import com.github.abjfh.fpa.impl.ForwardingPortArray;
import com.github.abjfh.fpa.impl.TrieToFPAConverter;
import com.github.abjfh.util.ConverterUtil;
import com.github.abjfh.util.FileUtil;

import inet.ipaddr.AddressStringException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressSeqRange;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4AddressSeqRange;
import inet.ipaddr.ipv6.IPv6AddressSeqRange;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Threads(-1)
public class FPATreeBenchmark {
    public static void main(String[] args) throws RunnerException {
        Options opt =
                new OptionsBuilder()
                        .include(FPATreeBenchmark.class.getSimpleName())
                        .resultFormat(ResultFormatType.JSON)
                        .build();

        new Runner(opt).run();
    }

    FPATree<String> ipv4Tree;
    FPATree<String> ipv6Tree;

    Random random = new Random(2);
    byte[] ipv4 = new byte[4];
    byte[] ipv6 = new byte[16];

    @Setup
    public void setup() throws Exception {
        List<IpSegment<String>> ipv6_list =
                FileUtil.loadCsvFile("data/ipv6_source.txt", "\\|").parallelStream()
                        .flatMap(
                                row -> {
                                    try {
                                        IPAddress startIp = new IPAddressString(row[0]).toAddress();
                                        IPAddress endIp = new IPAddressString(row[1]).toAddress();
                                        IPAddressSeqRange range;
                                        if (startIp.isIPv4() && endIp.isIPv4()) {
                                            range =
                                                    new IPv4AddressSeqRange(
                                                            startIp.toIPv4(), endIp.toIPv4());
                                        } else if (startIp.isIPv6() && endIp.isIPv6()) {
                                            range =
                                                    new IPv6AddressSeqRange(
                                                            startIp.toIPv6(), endIp.toIPv6());
                                        } else {
                                            return Stream.empty();
                                        }
                                        String result =
                                                String.join(
                                                        ",",
                                                        Arrays.copyOfRange(row, 2, row.length));
                                        Stream.Builder<IpSegment<String>> builder =
                                                Stream.builder();
                                        for (IPAddress ipAddress : range.spanWithPrefixBlocks()) {
                                            builder.add(
                                                    new IpSegment<>(ipAddress.toString(), result));
                                        }
                                        return builder.build();

                                    } catch (AddressStringException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .collect(Collectors.toList());
        BitTrie<String> ipv6_trie = ConverterUtil.convertToBitTrie(ipv6_list);
        ForwardingPortArray<String> ipv6_fpa =
                ConverterUtil.convertToForwardingPortArray(
                        TrieToFPAConverter.IP_TYPE.IPV6, ipv6_trie);
        ipv6Tree = ConverterUtil.convertToFPATree(ipv6_fpa);

        List<IpSegment<String>> ipv4_list =
                FileUtil.loadCsvFile("data/aspat.csv").parallelStream()
                        .map(
                                row -> {
                                    IpSegment<String> segment = new IpSegment<>();
                                    segment.setPrefixIp(row[0]);
                                    segment.setValue(row[1]);
                                    return segment;
                                })
                        .collect(Collectors.toList());

        BitTrie<String> ipv4_trie = ConverterUtil.convertToBitTrie(ipv4_list);
        ForwardingPortArray<String> ipv4_fpa =
                ConverterUtil.convertToForwardingPortArray(
                        TrieToFPAConverter.IP_TYPE.IPV4, ipv4_trie);
        ipv4Tree = ConverterUtil.convertToFPATree(ipv4_fpa);
    }

    @Setup(Level.Invocation)
    public void getIp() {
        random.nextBytes(ipv4);
        random.nextBytes(ipv6);
    }

    @Benchmark
    public void benchmarkIpv4Search(Blackhole bh) {
        bh.consume(ipv4Tree.search(ipv4));
    }

    @Benchmark
    public void benchmarkIpv6Search(Blackhole bh) {
        bh.consume(ipv6Tree.search(ipv6));
    }
}
