package com.github.abjfh.domain;

import java.util.Objects;

public class IpSegment<V> {
    private String prefixIp;
    private V value;

    public IpSegment() {}

    public IpSegment(String prefixIp, V value) {
        this.prefixIp = prefixIp;
    }

    public String getPrefixIp() {
        return prefixIp;
    }

    public void setPrefixIp(String prefixIp) {
        this.prefixIp = prefixIp;
    }

    public V getValue() {
        return value;
    }

    public void setValue(V value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        IpSegment<?> ipSegment = (IpSegment<?>) o;
        return Objects.equals(prefixIp, ipSegment.prefixIp)
                && Objects.equals(value, ipSegment.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefixIp, value);
    }
}
