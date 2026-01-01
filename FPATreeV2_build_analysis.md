# FPATreeV2.java build 方法深度分析

## 1. 输入参数结构分析

### ForwardingPortArray<V> 结构

- **bitSet**: BitSet 类型，用于位操作
- **table**: ArrayList<FPANode<V>>，是主要的数据结构
    - 容量：`1 << depth`（2的depth次方）
    - 初始时所有元素都指向同一个root节点
    - 每个FPANode可能包含：
        - **value**: 值（可能是最终要存储的值）
        - **next**: 指向下一层的ForwardingPortArray（构成三层结构）

### FPANode<V> 结构

```java
public static class FPANode<V> {
    V value;          // 终端值
    ForwardingPortArray<V> next;  // 下一层指针
}
```

## 2. 三层嵌套循环结构详解

### Layer 1 (外层循环)

```java
for (int i = 0; i < size; i++) {
    // i代表L1的16位int值
    int group1 = i >> 6;        // 取高6位 (0-1023)
    int bit1 = i & 0b111111;     // 取低6位 (0-63)
    
    ForwardingPortArray.FPANode<V> node = fpa.table.get(i);
    if (node.next != null) {
        // 需要构建Layer 2
    }
}
```

- **遍历范围**: 0到size-1
- **分组策略**: 1024个group (6位)，每个group 64个元素 (6位)
- **作用**: 处理第一层的数据结构

### Layer 2 (中层循环)

```java
ForwardingPortArray<V> fpa_l2 = node.next;
ChunkEntry[] group2 = new ChunkEntry[4];
for (int j = 0; j < 4; j++) {
    short[] codeWords = new short[8];
    List<Integer> lookupEntries = new LinkedList<>();
    // ...
    for (int k = 0; k < 64; k++) {
        int idx = j * 64 + k;
        // 处理每个元素
    }
}
```

- **遍历范围**: 4个group (2位)，每个group 64个元素
- **分组策略**: cluster2 (高3位) 和 bit2 (低3位)
- **作用**: 处理第二层的数据结构

### Layer 3 (内层循环)

```java
ForwardingPortArray<V> fpa_l3 = nextNodeL2.next;
ChunkEntry[] group3 = new ChunkEntry[4];
for (int j3 = 0; j3 < 4; j3++) {
    short[] codeWordsL3 = new short[8];
    // ...
    for (int k3 = 0; k3 < 64; k3++) {
        int idxL3 = j3 * 64 + k3;
        int cluster3 = (idxL3 >> 3) & 0b111;
        int bit3 = idxL3 & 0b111;
        // 处理每个元素
    }
}
```

- **遍历范围**: 4个group (2位)，每个group 64个元素
- **分组策略**: cluster3 (高3位) 和 bit3 (低3位)
- **作用**: 处理第三层（最细粒度）的数据结构

## 3. 核心数据结构详解

### rootChunk 结构

```java
int[][] rootChunk = new int[LAYER1_GROUP_COUNT][GROUP_SIZE];
// 1024组 × 64元素
```

- **维度**: 1024 × 64
- **用途**: 存储第一层的索引信息
- **编码**: 使用16位整数存储

### ChunkEntry 结构

```java
static class ChunkEntry {
    short[] codeWord;      // 位图和压缩信息
    int[] lookupEntries;   // 查找表条目
}
```

#### codeWord 结构分析

- **数据类型**: `short[8]`，每个short 16位
- **编码格式**:
    - 高8位：位图信息（表示哪些位置有值）
    - 低8位：before计数（前面有多少个有效元素）
- **位图编码**:
    - 使用 `^=` 操作进行异或操作（Layer 2）
    - 使用 `|=` 操作进行或操作（Layer 3）
    - 位图从右往左数，第bit3位置的bit值置为1

#### lookupEntries 结构分析

- **数据类型**: `int[]`
- **用途**: 存储实际值的索引或指针
- **索引机制**:
    - 0: 表示null值
    - > 0: 表示在resultList中的索引位置

### idxTable 的作用

```java
Hashtable<V, Integer> idxTable = new Hashtable<>();
```

- **用途**: 值去重
- **机制**:
    - 如果值不存在，添加到resultList并返回新索引
    - 如果值已存在，返回已有索引
- **优势**: 避免重复存储相同的值，节省空间

### resultList 的作用

```java
List<V> resultList = new ArrayList<>();
```

- **用途**: 存储所有唯一的值
- **特点**:
    - 按添加顺序存储
    - 通过idxTable进行索引管理
    - 实现了值的去重和统一管理

## 4. 压缩机制详解

### codeWord 位图编码

```java
// Layer 2 的编码方式（异或）
codeWords[cluster2] ^= (short) (bit2 << 8);

// Layer 3 的编码方式（或）
codeWordsL3[cluster3] |= (short) (1 << 8 << (8 - bit3 - 1));
```

### before 计数机制

```java
// 将 before 设置为本组中所有前面的簇中的 1 的个数
codeWordsL3[cluster3] |= (short) (countL3);
```

- **计算方式**: 统计当前簇前面所有簇中1的个数
- **作用**: 加速查找，知道跳过多少个无效元素

### 稀疏节点处理

```java
// TODO 实现稀疏节点的优化
```

当前代码中标记了TODO，表示稀疏节点优化尚未实现。

### 稀疏结构的处理逻辑

1. **类型标识**（注释中提到）：
    - 前2位表示类型：
        - 00: 值索引
        - 01: 非稀疏GroupList索引
        - 10: 稀疏GroupList索引

2. **当前实现**：
    - 主要使用非稀疏结构
    - 稀疏优化待实现

## 5. 关键算法流程

### 值索引分配流程

```java
idxTable.compute(nextNodeL3.value, (v_, idx_) -> {
    if (idx_ == null) {
        tree.resultList.add(v_);
        return tree.resultList.size() - 1;
    } else {
        return idx_;
    }
});
```

### 位图构建流程

1. **检测变化**: 使用 `Objects.equals()` 检测节点是否变化
2. **设置位图**: 根据位置设置相应的位
3. **记录索引**: 将值索引添加到lookupEntries
4. **更新计数**: 更新before计数
5. **合并信息**: 每8个元素更新一次before信息

### 分组层次总结

```
Layer 1: 1024 groups × 64 elements (6 bits + 6 bits)
Layer 2:    4 groups × 64 elements (3 bits + 3 bits)  
Layer 3:    4 groups × 64 elements (3 bits + 3 bits)
```

## 6. 未完成部分（TODO）

### 稀疏节点优化

- **位置**: 第108行
- **内容**: "TODO 实现稀疏节点的优化"
- **意义**:
    - 当前实现没有利用稀疏性优化
    - 对于大量null值的情况，可以大幅压缩存储空间
    - 需要实现稀疏GroupList的存储和访问逻辑

### 完整性分析

1. **基本功能**: ✓ 已实现三层结构的构建
2. **位图压缩**: ✓ 已实现基本的位图编码
3. **值去重**: ✓ 通过idxTable和resultList实现
4. **稀疏优化**: ✗ 待实现
5. **Layer 1的根节点处理**: ✗ 当前代码未实现rootChunk的填充

## 7. 性能特点

### 时间复杂度

- 最坏情况: O(n × 4 × 64) ≈ O(256n)
- 平均情况: 取决于数据稀疏性

### 空间复杂度

- rootChunk: 1024 × 64 × 4 bytes = 256KB
- chunkList: 动态增长，取决于实际数据
- resultList: 取决于唯一值的数量

### 优势

1. **快速查找**: 通过位图快速定位有效数据
2. **内存效率**: 值去重减少重复存储
3. **层次化**: 三层结构平衡查找效率和存储空间

### 潜在优化点

1. **稀疏性优化**: 实现稀疏节点处理
2. **内存对齐**: 考虑缓存行对齐
3. **压缩算法**: 使用更高效的压缩算法