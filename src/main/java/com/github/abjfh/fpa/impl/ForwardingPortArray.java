package com.github.abjfh.fpa.impl;

import com.github.abjfh.fpa.IpSearcher;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

public class ForwardingPortArray<V> implements IpSearcher<V> {

    BitSet bitSet;
    List<FPANode<V>> table;
    int depth; // 当前层处理的位数

    public ForwardingPortArray(FPANode<V> root, int depth) {
        this.depth = depth;
        int capacity = 1 << depth;
        this.table = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            this.table.add(root);
        }
    }

    public static class FPANode<V> {
        V value;
        ForwardingPortArray<V> next;

        public FPANode() {}

        public FPANode(V value) {
            this.value = value;
        }

        public FPANode(ForwardingPortArray<V> next) {
            this.next = next;
        }

        public FPANode(V value, ForwardingPortArray<V> next) {
            this.value = value;
            this.next = next;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            FPANode<?> fpaNode = (FPANode<?>) o;
            return Objects.equals(value, fpaNode.value) && Objects.equals(next, fpaNode.next);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, next);
        }
    }

    /**
     * 搜索 IP 地址对应的值（最长前缀匹配）
     *
     * @param ipBytes IP 地址的字节数组
     * @return 查找到的值，未找到返回 null
     */
    @Override
    public V search(byte[] ipBytes) {
        V lastFoundValue = null;
        ForwardingPortArray<V> current = this;
        int byteOffset = 0;
        int bitOffset = 0;

        while (current != null && byteOffset < ipBytes.length) {
            // 使用当前层的深度
            int currentDepth = current.depth;

            // 计算当前层的索引
            int index = extractBits(ipBytes, byteOffset, bitOffset, currentDepth);
            FPANode<V> node = current.table.get(index);

            // 记录当前节点的值（如果非 null）
            if (node.value != null) {
                lastFoundValue = node.value;
            }

            // 移动到下一层
            byteOffset += (bitOffset + currentDepth) / 8;
            bitOffset = (bitOffset + currentDepth) % 8;
            current = node.next;
        }

        return lastFoundValue;
    }

    /**
     * 从字节数组中指定位偏移处提取指定位数的值
     *
     * @param bytes 字节数组
     * @param byteOffset 字节偏移
     * @param bitOffset 位偏移（0-7）
     * @param bitCount 要提取的位数
     * @return 提取的整数值
     */
    private int extractBits(byte[] bytes, int byteOffset, int bitOffset, int bitCount) {
        int result = 0;
        int bitsExtracted = 0;

        while (bitsExtracted < bitCount) {
            int currentByte = bytes[byteOffset] & 0xFF;
            int bitsAvailable = 8 - bitOffset;
            int bitsToExtract = Math.min(bitsAvailable, bitCount - bitsExtracted);

            // 提取指定位
            int mask = (1 << bitsToExtract) - 1;
            int shifted = currentByte >>> (bitsAvailable - bitsToExtract);
            int extracted = shifted & mask;

            result = (result << bitsToExtract) | extracted;
            bitsExtracted += bitsToExtract;

            // 移动到下一个字节
            byteOffset++;
            bitOffset = 0;
        }

        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ForwardingPortArray<?> that = (ForwardingPortArray<?>) o;
        return Objects.equals(bitSet, that.bitSet) && Objects.equals(table, that.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bitSet, table);
    }
}
