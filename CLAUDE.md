# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建与测试

### Maven 命令
```bash
# 编译项目
mvn compile

# 运行测试
mvn test

# 编译测试代码
mvn test-compile

# 使用 exec 插件运行 Java 应用
mvn exec:java -Dexec.mainClass="com.github.abjfh.YourMainClass"
```

## 项目架构

这是一个高性能 IP 前缀查找和路由表查询项目，实现了基于位 Trie 的数据结构优化方案。

### 核心数据结构流程

1. **BitTrie** (`BitTrie.java`)
   - 基础的位 Trie 树结构，用于存储 IP 前缀
   - 支持按位插入和最长前缀匹配查找
   - 每个节点包含 leftChild (位0) 和 rightChild (位1)

2. **ForwardingPortArray (FPA)** (`ForwardingPortArray.java`)
   - 分层转发端口数组，通过 `TrieToFPAConverter` 从 BitTrie 转换而来
   - 分层深度配置为 `[16, 8, 8]` (共32位，对应IPv4)
   - 每层将前缀空间划分为更细粒度的子空间
   - 节点可以存储最终值或指向下一层的引用

3. **TrieToFPAConverter** (`TrieToFPAConverter.java`)
   - 将 BitTrie 转换为分层 ForwardingPortArray 的转换器
   - 使用广度优先遍历按层级填充 FPA 结构
   - 处理叶子节点值和创建下一层 FPA 的逻辑

4. **FPATreeV2** (`FPATreeV2.java`) - 当前正在开发
   - FPA 的压缩优化版本
   - 使用稀疏位图和分块存储来减少内存占用
   - 分为 3 层：Layer1 (1024组×64元素), Layer2/3 (4组×64元素)
   - 采用 codeWord 编码和 lookupEntry 索引机制
   - `INDEX_TABLE` 用于快速计算位图中的1的个数

### 数据存储

项目使用 CSV 文件存储路由表数据（位于 `data/` 目录，被 .gitignore 忽略）：
- `aspat.csv`: AS-PATH 前缀数据，格式为 `前缀/长度,AS路径`
- `BGP_INFO_IPV4.csv`: BGP 路由信息，包含起始IP、结束IP、前缀长度、省份等

### 依赖项

- **ipaddress** (5.5.1): IP 地址处理库
- **lombok** (1.18.42): 简化 Java 代码
- **junit** (4.13.2): 单元测试
- **JMH** (1.37): Java 基准测试框架

### 开发状态

当前主要开发文件是 `FPATreeV2.java`，实现了 FPA 的压缩版本。代码中标记了 `TODO` 表示需要实现稀疏节点的优化。

Git 提交历史显示项目处于快速迭代开发阶段，最近的提交标记为 `tmp5`, `tmp4`, `tmp3` 等。
