package com.github.abjfh;

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
}
