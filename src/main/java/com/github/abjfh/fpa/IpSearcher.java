package com.github.abjfh.fpa;

public interface IpSearcher<V> {
    V search(byte[] ipBytes) throws Exception;
}
