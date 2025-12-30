package com.github.abjfh;

import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressSeqRange;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.Queue;

public class Application {
    private static final int DEPTH_L1 = 16;
    private static final int DEPTH_L2 = 8;
    private static final int DEPTH_L3 = 8;
    private static final int MAX_PORT = 65535;

    public static void main(String[] args) throws Exception {
        BitTrie<String[]> bitTrie = new BitTrie<>();
        buildTrie(bitTrie);

        // 16 + 8 + 8
        ForwardingPortArray.FPANode<String[]> fpaL1Root = new ForwardingPortArray.FPANode<>();
        ForwardingPortArray<String[]> fpaL1 = new ForwardingPortArray<>(fpaL1Root, DEPTH_L1);

        Queue<BitTrie.TrieNodeWrapper<String[]>> queueL1 = new LinkedList<>();
        queueL1.offer(new BitTrie.TrieNodeWrapper<>(bitTrie.root, 0, MAX_PORT));
        int trieDepth = 0;

        while (!queueL1.isEmpty()) {
            int levelSize = queueL1.size();
            for (int i = 0; i < levelSize; i++) {
                BitTrie.TrieNodeWrapper<String[]> currentNode = queueL1.poll();
                assert currentNode != null;
                if (trieDepth < DEPTH_L1) {
                    // 计算中间值，用于分割左右边界
                    int mid = (currentNode.leftBound + currentNode.rightBound) / 2;

                    if (currentNode.node.leftChild != null) {
                        // 左子节点的区间：[父节点的leftBound, mid]
                        queueL1.offer(
                                new BitTrie.TrieNodeWrapper<>(
                                        currentNode.node.leftChild,
                                        currentNode.leftBound,
                                        mid));
                    }
                    if (currentNode.node.rightChild != null) {
                        // 右子节点的区间：[mid + 1, 父节点的rightBound]
                        queueL1.offer(
                                new BitTrie.TrieNodeWrapper<>(
                                        currentNode.node.rightChild,
                                        mid + 1,
                                        currentNode.rightBound));
                    }
                }

                // 最长前缀匹配, 只有叶子节点需要更新值
                ForwardingPortArray.FPANode<String[]> newNode = new ForwardingPortArray.FPANode<>();
                if (trieDepth <= DEPTH_L1 && currentNode.node.isLeaf) {
                    newNode.value = currentNode.node.value;
                    for (int j = currentNode.leftBound;
                            j <= currentNode.rightBound;
                            j++) {
                        fpaL1.table.set(j, newNode);
                    }
                }

                if (trieDepth == DEPTH_L1 && currentNode.node.hasChild()) {
                    assert currentNode.leftBound == currentNode.rightBound;

                    ForwardingPortArray.FPANode<String[]> node =
                            fpaL1.table.get(currentNode.leftBound);

                    ForwardingPortArray<String[]> next =
                            new ForwardingPortArray<>(
                                    new ForwardingPortArray.FPANode<>(node.value), DEPTH_L2);

                    fpaL1.table.set(
                            currentNode.leftBound,
                            new ForwardingPortArray.FPANode<>(node.value, next));
                    // TODO 参考l1 填充l2
                }
            }
            trieDepth++;
        }
        fpaL1.compactTable();
        System.out.println(trieDepth);
    }

    private static void buildTrie(BitTrie<String[]> bitTrie) throws Exception {
        try (BufferedReader br = Files.newBufferedReader(Paths.get("data/BGP_INFO_IPV4.csv"))) {
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
