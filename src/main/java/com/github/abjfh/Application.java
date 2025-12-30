package com.github.abjfh;

import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressSeqRange;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Application {
    public static void main(String[] args) throws Exception {
        BitTrie<String[]> bitTrie = new BitTrie<>();
        buildTrie(bitTrie);

        // 使用转换器将 BitTrie 转换为分层 ForwardingPortArray
        // 深度配置: 16 + 8 + 8 = 32 位
        TrieToFPAConverter<String[]> converter = new TrieToFPAConverter<>(new int[] {16, 8, 8});
        ForwardingPortArray<String[]> result = converter.convert(bitTrie);

        System.out.println("转换完成");
    }

    private static void buildTrie(BitTrie<String[]> bitTrie) throws Exception {
        try (BufferedReader br = Files.newBufferedReader(Paths.get("data/BGP_INFO_IPV4.csv.bak"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] row = line.split(",");
                IPv4Address[] withPrefixBlocks =
                        new IPv4AddressSeqRange(
                                        new IPAddressString(row[0]).toAddress().toIPv4(),
                                        new IPAddressString(row[1]).toAddress().toIPv4())
                                .spanWithPrefixBlocks();
                for (IPv4Address address : withPrefixBlocks) {
                    bitTrie.put(address.getBytes(), address.getPrefixLength(), row);
                }
            }
        }
    }
}
