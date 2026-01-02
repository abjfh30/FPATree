package com.github.abjfh;

import java.util.LinkedList;
import java.util.Queue;

/** 将 BitTrie 转换为分层 ForwardingPortArray 的转换器 */
public class TrieToFPAConverter {
    private static final int[] depths = new int[] {16, 8, 8};

    /** 创建转换器 */
    private TrieToFPAConverter() {}

    /**
     * 将 BitTrie 转换为 ForwardingPortArray
     *
     * @param bitTrie 要转换的 BitTrie
     * @return 转换后的 ForwardingPortArray
     */
    public static <V> ForwardingPortArray<V> convert(BitTrie<V> bitTrie) {

        int firstDepth = depths[0];

        ForwardingPortArray.FPANode<V> root = new ForwardingPortArray.FPANode<>();
        ForwardingPortArray<V> fpa = new ForwardingPortArray<>(root, firstDepth);

        fillLevel(fpa, bitTrie.root, firstDepth, 1);
        return fpa;
    }

    /**
     * 填充指定层级的 ForwardingPortArray
     *
     * @param fpa 要填充的 ForwardingPortArray
     * @param trieNode 对应的 Trie 节点
     * @param depth 当前层的深度
     * @param nextDepthIndex 下一层在 depths 数组中的索引
     */
    private static <V> void fillLevel(
            ForwardingPortArray<V> fpa,
            BitTrie.TrieNode<V> trieNode,
            int depth,
            int nextDepthIndex) {
        Queue<BitTrie.TrieNodeWrapper<V>> queue = new LinkedList<>();
        int maxIndex = (1 << depth) - 1;
        queue.offer(new BitTrie.TrieNodeWrapper<>(trieNode, 0, maxIndex));
        int currentDepth = 0;

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                BitTrie.TrieNodeWrapper<V> currentNode = queue.poll();
                assert currentNode != null;

                if (currentDepth < depth) {
                    int mid = (currentNode.leftBound + currentNode.rightBound) / 2;

                    if (currentNode.node.leftChild != null) {
                        queue.offer(
                                new BitTrie.TrieNodeWrapper<>(
                                        currentNode.node.leftChild, currentNode.leftBound, mid));
                    }
                    if (currentNode.node.rightChild != null) {
                        queue.offer(
                                new BitTrie.TrieNodeWrapper<>(
                                        currentNode.node.rightChild,
                                        mid + 1,
                                        currentNode.rightBound));
                    }
                }

                // 有值的节点处理：填充所有有值的节点（包括有子节点的中间节点）
                // 这确保了中间节点的值被正确传播到 FPA
                ForwardingPortArray.FPANode<V> newNode = new ForwardingPortArray.FPANode<>();
                if (currentDepth <= depth && currentNode.node.isLeaf) {
                    newNode.value = currentNode.node.value;
                    for (int j = currentNode.leftBound; j <= currentNode.rightBound; j++) {
                        fpa.table.set(j, newNode);
                    }
                }

                // 创建下一层
                if (currentDepth == depth
                        && currentNode.node.hasChild()
                        && nextDepthIndex < depths.length) {
                    assert currentNode.leftBound == currentNode.rightBound;

                    ForwardingPortArray.FPANode<V> node = fpa.table.get(currentNode.leftBound);
                    int nextDepth = depths[nextDepthIndex];

                    ForwardingPortArray<V> nextFPA =
                            new ForwardingPortArray<>(
                                    new ForwardingPortArray.FPANode<>(node.value), nextDepth);

                    fpa.table.set(
                            currentNode.leftBound,
                            new ForwardingPortArray.FPANode<>(node.value, nextFPA));

                    // 递归填充下一层
                    fillLevel(nextFPA, currentNode.node, nextDepth, nextDepthIndex + 1);
                }
            }
            currentDepth++;
        }
    }
}
