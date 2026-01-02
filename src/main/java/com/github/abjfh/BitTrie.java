package com.github.abjfh;

import java.util.Objects;

public class BitTrie<V> {
    TrieNode<V> root;

    static class TrieNode<V> {
        V value;
        boolean isLeaf = false;
        TrieNode<V> leftChild; // 对应位0
        TrieNode<V> rightChild; // 对应位1

        public boolean hasChild() {
            return leftChild != null || rightChild != null;
        }
    }

    static class TrieNodeWrapper<V> {
        TrieNode<V> node;
        int leftBound;
        int rightBound;

        TrieNodeWrapper(TrieNode<V> node, int leftBound, int rightBound) {
            this.node = node;
            this.leftBound = leftBound;
            this.rightBound = rightBound;
        }
    }

    public BitTrie() {
        root = new TrieNode<>();
    }

    /**
     * 将前缀键插入到Trie中
     *
     * @param prefixKey 字节数组表示的键
     * @param prefixLength 前缀长度（位数）
     * @param value 要存储的值
     */
    public void put(byte[] prefixKey, int prefixLength, V value) {
        if (prefixKey == null) {
            throw new IllegalArgumentException("prefixKey cannot be null");
        }
        if (prefixLength < 0 || prefixLength > prefixKey.length * 8) {
            throw new IllegalArgumentException(
                    "prefixLength must be between 0 and " + (prefixKey.length * 8));
        }
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }

        TrieNode<V> node = root;

        // 遍历前缀的每一位
        for (int i = 0; i < prefixLength; i++) {
            // 父节点已经存储时，不需要创建对应字节点
            if (node.isLeaf && Objects.equals(node.value, value)) {
                return;
            }

            // 计算当前位在字节数组中的位置
            int byteIndex = i / 8;
            if (byteIndex >= prefixKey.length) {
                throw new IllegalArgumentException("Prefix length exceeds key length");
            }

            int bitPosition = 7 - (i % 8); // 从最高位开始
            byte currentByte = prefixKey[byteIndex];
            boolean isRight = ((currentByte >> bitPosition) & 1) == 1;

            if (isRight) {
                // 位为1，向右子树
                if (node.rightChild == null) {
                    node.rightChild = new TrieNode<>();
                }
                node = node.rightChild;
            } else {
                // 位为0，向左子树
                if (node.leftChild == null) {
                    node.leftChild = new TrieNode<>();
                }
                node = node.leftChild;
            }
        }

        // 到达前缀终点，设置值并标记为叶子节点
        node.value = value;
        node.isLeaf = true;
    }

    /**
     * 查找与给定键匹配的最长前缀对应的值
     *
     * @param key 要查找的键
     * @return 匹配的最长前缀的值，如果没有则返回null
     */
    public V get(byte[] key) {
        if (key == null || key.length == 0) {
            return null;
        }

        TrieNode<V> node = root;
        V lastFoundValue = null;

        // 检查root节点是否是叶子节点（处理/0前缀）
        if (node.isLeaf) {
            lastFoundValue = node.value;
        }

        // 遍历键的每一位（最多32位，即4个字节）
        for (int i = 0; i < Math.min(key.length * 8, 32); i++) {
            int byteIndex = i / 8;
            int bitPosition = 7 - (i % 8);
            byte currentByte = key[byteIndex];
            boolean isRight = ((currentByte >> bitPosition) & 1) == 1;

            if (isRight) {
                if (node.rightChild == null) break;
                node = node.rightChild;
            } else {
                if (node.leftChild == null) break;
                node = node.leftChild;
            }

            // 记录遇到的最后一个叶子节点的值
            if (node.isLeaf) {
                lastFoundValue = node.value;
            }
        }

        return lastFoundValue;
    }

    /**
     * 压缩Trie树，优化节点结构：
     * 1. 当父节点已经存储时，不需要创建对应子节点（在put方法中已实现）
     * 2. 当左右节点都指向同一个值时，删除子节点，将父节点指向该值
     */
    public void compress() {
        compressNode(root);
    }

    /**
     * 递归压缩节点
     * 采用后序遍历：先处理子节点，再处理当前节点
     */
    private void compressNode(TrieNode<V> node) {
        if (node == null) {
            return;
        }

        // 先递归处理左右子节点
        compressNode(node.leftChild);
        compressNode(node.rightChild);

        // 检查是否可以压缩：左右子节点都存在且都指向相同值
        if (node.leftChild != null && node.rightChild != null) {
            // 如果两个子节点都是叶子节点，并且它们的值相同
            if (node.leftChild.isLeaf && node.rightChild.isLeaf &&
                    Objects.equals(node.leftChild.value, node.rightChild.value)) {

                // 将值提升到当前节点
                node.value = node.leftChild.value;
                node.isLeaf = true;

                // 删除子节点
                node.leftChild = null;
                node.rightChild = null;
            }
        }
    }
}
