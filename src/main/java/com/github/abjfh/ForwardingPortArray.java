package com.github.abjfh;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

public class ForwardingPortArray<V> {

    BitSet bitSet;
    List<FPANode<V>> table;

    public ForwardingPortArray(FPANode<V> root, int depth) {
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
