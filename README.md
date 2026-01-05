# IpSearcher

高性能 IP 地址查询引擎，支持 IPv4 和 IPv6 地址的快速检索和前缀匹配。

## 项目简介

IpSearcher 是一个基于 Java 实现的 IP 地址搜索引擎，通过多层压缩的数据结构实现高效的 IP
地址到值的映射。项目实现了三种核心数据结构，并针对内存占用和查询速度进行了深度优化。

## 核心功能

- 支持 IPv4 和 IPv6 地址查询
- 支持 IP 段（CIDR）前缀匹配
- 内存统计功能
- JMH 性能基准测试

## 数据结构

### 1. BitTrie

位压缩的字典树结构，通过按位遍历 IP 地址实现精确的前缀匹配。

### 2. ForwardingPortArray (FPA)

转发端口数组，将字典树结构转换为多层数组结构，实现字典树的线性存储。

### 3. FPATree

**项目的核心实现**，采用三层压缩结构：

- **Layer 1**: Root Chunk - 65536 个 int 的根节点数组
- **Layer 2/3**: Chunk Array - 支持两种存储模式
    - **Dense Chunk**: 密集存储，适用于节点较多的情况，使用位图压缩
    - **Sparse Chunk**: 稀疏存储，适用于节点稀疏的情况，自动从 Dense 退化

#### FPATree 优化特性

- 位图压缩：CodeWord 编码减少内存占用
- 稀疏结构优化：自动检测并退化到稀疏存储
- 扁平化存储：减少对象开销，提高内存连续性
- 查询速度：单线程 **10M ops/s** (已提供基准测试数据)

## 快速开始

### 环境要求

- Java 8 或更高版本
- Maven 3.x

## 项目结构

```
IpSearcher/
├── src/main/java/com/github/abjfh/
│   ├── Application.java           # 主程序入口
│   ├── impl/
│   │   └── BitTrie.java               # 位压缩字典树
│   │   └── ForwardingPortArray.java   # 转发端口数组
│   │   └── FPATree.java               # FPA树核心实现
│   │   └── TrieToFPAConverter.java    # Trie到FPA转换器
│   │   └── IpSearcher.java            # 查询接口
│   ├── domain/
│   │   └── IpSegment.java         # IP段数据模型
│   ├── util/
│   │   ├── ConverterUtil.java     # 转换工具类
│   │   └── FileUtil.java          # 文件工具类
│   └── jmh/
│       └── FPATreeBenchmark.java  # JMH基准测试
├── data/                          # 测试数据目录
└── pom.xml                        # Maven配置
```

## 依赖项

- [ipaddress](https://github.com/seancfoley/ipaddress) - IP 地址处理库
- [JMH](https://openjdk.org/projects/code-tools/jmh/) - Java 基准测试工具

## 数据格式

项目支持从 CSV 文件加载 IP 段数据：

- IPv4: `IP地址,值`
- IPv6: `起始IP|结束IP|值`

示例：

```csv
192.168.1.0,192.168.1.255,网段A
2001:db8::,2001:db8:ffff:ffff:ffff:ffff:ffff:ffff,IPv6网段
```

## API 使用示例

```java
// 创建 IP 段列表
List<IpSegment<String>> segments = Arrays.asList(
                new IpSegment<>("192.168.1.0/24", "Network A"),
                new IpSegment<>("10.0.0.0/8", "Network B")
        );

// 转换为 BitTrie
BitTrie<String> trie = ConverterUtil.convertToBitTrie(segments);

// 转换为 ForwardingPortArray
ForwardingPortArray<String> fpa = ConverterUtil.convertToForwardingPortArray(
        TrieToFPAConverter.IP_TYPE.IPV4, trie
);

// 转换为 FPATree（最高性能）
FPATree<String> tree = ConverterUtil.convertToFPATree(fpa);

// 查询
byte[] ipBytes = new byte[]{(byte) 192, (byte) 168, 1, 1};
String result = tree.search(ipBytes);

// 打印内存统计
tree.

printMemoryStats();
```

## 许可证

本项目采用开源许可证，具体请查看项目文件。

## 作者

abjfh
