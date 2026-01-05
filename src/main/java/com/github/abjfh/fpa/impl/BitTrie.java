package com.github.abjfh.fpa.impl;

import com.github.abjfh.fpa.IpSearcher;

import java.util.*;
import java.util.function.BiConsumer;

public class BitTrie<V> implements IpSearcher<V> {
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
     * @param ipBytes 要查找的键
     * @return 匹配的最长前缀的值，如果没有则返回null
     */
    @Override
    public V search(byte[] ipBytes) {
        if (ipBytes == null || ipBytes.length == 0) {
            return null;
        }

        TrieNode<V> node = root;
        V lastFoundValue = null;

        // 检查root节点是否是叶子节点（处理/0前缀）
        if (node.isLeaf) {
            lastFoundValue = node.value;
        }

        for (int i = 0; i < ipBytes.length * 8; i++) {
            int byteIndex = i / 8;
            int bitPosition = 7 - (i % 8);
            byte currentByte = ipBytes[byteIndex];
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

    /** 压缩Trie树，优化节点结构： 1. 当父节点已经存储时，不需要创建对应子节点（在put方法中已实现） 2. 当左右节点都指向同一个值时，删除子节点，将父节点指向该值 */
    public void compress() {
        compressNode(root);
    }

    /**
     * 递归压缩节点 采用后序遍历：先处理子节点，再处理当前节点
     *
     * <p>压缩原则：不破坏最长前缀匹配规则 - 只有当子节点不会造成更短前缀覆盖更长前缀时，才能压缩
     */
    private void compressNode(TrieNode<V> node) {
        if (node == null) {
            return;
        }

        // 先递归处理左右子节点
        compressNode(node.leftChild);
        compressNode(node.rightChild);

        // 场景1：左右子节点都存在且都是叶子节点且值相同 -> 可以合并
        if (node.leftChild != null && node.rightChild != null) {
            if (node.leftChild.isLeaf
                    && node.rightChild.isLeaf
                    && Objects.equals(node.leftChild.value, node.rightChild.value)) {

                node.value = node.leftChild.value;
                node.isLeaf = true;
                node.leftChild = null;
                node.rightChild = null;
            }
            return;
        }

        // 场景2：父节点是叶子节点，单子节点也是相同值的叶子节点 -> 可以删除子节点
        // 安全原因：父节点已经是叶子，任何匹配到父节点的查询都已返回该值，
        //          子节点只是冗余地延长了路径但不改变返回值
        if (node.isLeaf) {
            TrieNode<V> onlyChild = node.leftChild != null ? node.leftChild : node.rightChild;
            if (onlyChild != null
                    && onlyChild.isLeaf
                    && !onlyChild.hasChild()
                    && Objects.equals(node.value, onlyChild.value)) {
                node.leftChild = null;
                node.rightChild = null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void preorderTraversalIterative(BiConsumer<List<Boolean>, V> consumer) {

        // 使用栈存储节点、深度、路径和位序列
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {root, new ArrayList<Boolean>()});

        while (!stack.isEmpty()) {
            Object[] item = stack.pop();
            TrieNode<V> node = (TrieNode<V>) item[0];
            List<Boolean> path = (List<Boolean>) item[1];
            // 只记录真正的叶子节点
            if (node.isLeaf) {
                consumer.accept(path, node.value);
            }

            // 先压入右子节点，再压入左子节点（栈是LIFO）
            if (node.rightChild != null) {
                List<Boolean> newPath = new ArrayList<>(path);
                newPath.add(true);
                stack.push(new Object[] {node.rightChild, newPath});
            }
            if (node.leftChild != null) {
                List<Boolean> newPath = new ArrayList<>(path);
                newPath.add(false);
                stack.push(new Object[] {node.leftChild, newPath});
            }
        }
    }
}
