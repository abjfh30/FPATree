package com.github.abjfh;

import com.github.abjfh.domain.IpSegment;
import com.github.abjfh.fpa.*;
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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 测试 BitTrie、ForwardingPortArray 和 FPATree 的查询结果一致性 */
public class Application {

    public static void main(String[] args) throws Exception {
        assertIpv4Search(100_000_000);
        assertIpv6Search(100_000_000);
    }

    private static void assertIpv6Search(int testCount) throws Exception {
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
        FPATree<String> ipv6_tree = ConverterUtil.convertToFPATree(ipv6_fpa);
        ipv6_tree.printMemoryStats();
        assertResult(TrieToFPAConverter.IP_TYPE.IPV6, testCount, ipv6_trie, ipv6_fpa, ipv6_tree);
    }

    private static void assertIpv4Search(int testCount) throws Exception {
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
        FPATree<String> ipv4_tree = ConverterUtil.convertToFPATree(ipv4_fpa);
        ipv4_tree.printMemoryStats();
        assertResult(TrieToFPAConverter.IP_TYPE.IPV4, testCount, ipv4_trie, ipv4_fpa, ipv4_tree);
    }

    private static void assertResult(
            TrieToFPAConverter.IP_TYPE ipType, int testCount, IpSearcher<?>... ipSearchers)
            throws Exception {
        int length = ipSearchers.length;
        Object[] results = new Object[length];
        Random random = new Random(2);
        byte[] ip = new byte[0];
        switch (ipType) {
            case IPV4:
                ip = new byte[4];
                break;
            case IPV6:
                ip = new byte[16];
                break;
        }
        for (int i = 0; i < testCount; i++) {
            random.nextBytes(ip);
            for (int j = 0; j < length; j++) {
                results[j] = ipSearchers[j].search(ip);
            }
            if (!compareResults(results)) {
                System.out.println(Arrays.toString(ip) + "\t" + Arrays.toString(results));
            }
        }
    }

    private static boolean compareResults(Object... results) {
        for (Object result : results) {
            if (!Objects.equals(results[0], result)) {
                return false;
            }
        }
        return true;
    }
}
