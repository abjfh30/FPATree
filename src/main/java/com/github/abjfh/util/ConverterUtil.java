package com.github.abjfh.util;

import com.github.abjfh.domain.IpSegment;
import com.github.abjfh.fpa.impl.BitTrie;
import com.github.abjfh.fpa.impl.FPATree;
import com.github.abjfh.fpa.impl.ForwardingPortArray;
import com.github.abjfh.fpa.impl.TrieToFPAConverter;

import inet.ipaddr.AddressStringException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

import java.util.List;

public class ConverterUtil {
    public static <V> BitTrie<V> convertToBitTrie(List<IpSegment<V>> list)
            throws AddressStringException {
        BitTrie<V> bitTrie = new BitTrie<>();
        for (IpSegment<V> ipSegment : list) {
            IPAddress ipAddress = new IPAddressString(ipSegment.getPrefixIp()).toAddress();
            if (ipAddress != null) {
                bitTrie.put(
                        ipAddress.getBytes(), ipAddress.getPrefixLength(), ipSegment.getValue());
            }
        }
        bitTrie.compress();
        return bitTrie;
    }

    public static <V> ForwardingPortArray<V> convertToForwardingPortArray(
            TrieToFPAConverter.IP_TYPE ipType, BitTrie<V> bitTrie) {
        switch (ipType) {
            case IPV4:
                return TrieToFPAConverter.IPV4_CONVERTER.convert(bitTrie);
            case IPV6:
                return TrieToFPAConverter.IPV6_CONVERTER.convert(bitTrie);
        }
        return null;
    }

    public static <V> FPATree<V> convertToFPATree(ForwardingPortArray<V> fpa) {
        return FPATree.<V>Builder().fpa(fpa).build();
    }
}
