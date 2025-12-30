package com.github.abjfh;

import java.util.LinkedList;
import java.util.Queue;

/**
 * 将 BitTrie 转换为分层 ForwardingPortArray 的转换器
 *
 * @param <V> 存储的值类型
 */
public class TrieToFPAConverter<V> {
    private final int[] depths;

    /**
     * 创建转换器
     *
     * @param depths 每层的深度配置，例如 {16, 8, 8} 表示三层，深度分别为 16、8、8
     */
    public TrieToFPAConverter(int[] depths) {
        this.depths = depths;
    }

    /**
     * 将 BitTrie 转换为 ForwardingPortArray
     *
     * @param bitTrie 要转换的 BitTrie
     * @return 转换后的 ForwardingPortArray
     */
    public ForwardingPortArray<V> convert(BitTrie<V> bitTrie) {
        if (depths.length == 0) {
            throw new IllegalArgumentException("Depths array cannot be empty");
        }

        int firstDepth = depths[0];
        int maxIndex = (1 << firstDepth) - 1;

        ForwardingPortArray.FPANode<V> root = new ForwardingPortArray.FPANode<>();
        ForwardingPortArray<V> fpa = new ForwardingPortArray<>(root, firstDepth);

        fillLevel(fpa, bitTrie.root, firstDepth, 1);
        return fpa;
    }

    /**
     * 填充指定层级的 ForwardingPortArray
     *
     * @param fpa            要填充的 ForwardingPortArray
     * @param trieNode       对应的 Trie 节点
     * @param depth          当前层的深度
     * @param nextDepthIndex 下一层在 depths 数组中的索引
     */
    private void fillLevel(ForwardingPortArray<V> fpa,
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
                        queue.offer(new BitTrie.TrieNodeWrapper<>(
                                currentNode.node.leftChild,
                                currentNode.leftBound,
                                mid));
                    }
                    if (currentNode.node.rightChild != null) {
                        queue.offer(new BitTrie.TrieNodeWrapper<>(
                                currentNode.node.rightChild,
                                mid + 1,
                                currentNode.rightBound));
                    }
                }

                // 叶子节点处理
                ForwardingPortArray.FPANode<V> newNode = new ForwardingPortArray.FPANode<>();
                if (currentDepth <= depth && currentNode.node.isLeaf) {
                    newNode.value = currentNode.node.value;
                    for (int j = currentNode.leftBound; j <= currentNode.rightBound; j++) {
                        fpa.table.set(j, newNode);
                    }
                }

                // 创建下一层
                if (currentDepth == depth && currentNode.node.hasChild() && nextDepthIndex < depths.length) {
                    assert currentNode.leftBound == currentNode.rightBound;

                    ForwardingPortArray.FPANode<V> node = fpa.table.get(currentNode.leftBound);
                    int nextDepth = depths[nextDepthIndex];

                    ForwardingPortArray<V> nextFPA = new ForwardingPortArray<>(
                            new ForwardingPortArray.FPANode<>(node.value), nextDepth);

                    fpa.table.set(currentNode.leftBound,
                            new ForwardingPortArray.FPANode<>(node.value, nextFPA));

                    // 递归填充下一层
                    fillLevel(nextFPA, currentNode.node, nextDepth, nextDepthIndex + 1);
                }
            }
            currentDepth++;
        }
        fpa.compactTable();
    }
}
