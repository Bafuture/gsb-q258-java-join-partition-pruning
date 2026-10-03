# 分区表连接的剪枝组件

Pair-wise GSB 标注任务仓库（第 16 批 / 258）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们要连接两张按同一维度分区的表，先连接再过滤代价太高，应该提前把无关分区排除掉。请从零实现一个分区表连接的分区剪枝组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 两张表按相同分区键分区，支持在连接条件里同时包含分区键与非分区键；2) 支持分区剪枝：根据连接条件推导出两侧相关的分区对，无关分区不得参与扫描，需给出剪枝前后参与的分区对数量对比；3) 支持分区裁剪的边界推导：等值、范围与 IN 条件都要能推导出分区范围，需说明推导规则；4) 正确性：剪枝后的连接结果必须与全量连接再过滤的结果完全一致，需用随机数据验证；5) 支持条件无法推导分区时的兜底：退回全分区扫描并明确标注未能剪枝的原因；6) 支持分区对并行：不同分区对可以并行连接，需控制并发度；7) 提供统计：分区对总数、参与数、剪枝率与各分区对的处理条数；8) 测试覆盖三类条件的推导、剪枝正确性、兜底路径、并行连接与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

## 实现说明

核心代码位于 `src/main/java/com/example/gsb/join`：

| 类 | 职责 |
|----|------|
| `PartitionScheme` | 范围分区方案（半开区间 `[lo,hi)`，`Long.MAX_VALUE` 表示 +inf），支持等宽分区、二分定位、区间交集求分区 |
| `PartitionedTable` | 按分区键物理分发数据的表 |
| `Predicate` | sealed 条件模型：`KeyValue`（键-常量）、`KeyIn`（IN）、`KeyKey`（两侧键比较）、`Custom`（无法分析的黑盒条件） |
| `KeyDomain` | 分区键取值域（区间并集），负责边界推导与交/并 |
| `PartitionPruner` | 条件分析 -> 两侧候选分区 -> 分区对可行性过滤，输出 `PruningPlan`（含兜底原因与推导轨迹） |
| `PartitionJoinExecutor` | 按分区对并行嵌套循环连接，固定线程池控制并发度 |
| `JoinStats` | 分区对总数、参与数、剪枝率、各分区对评估/输出条数 |

### 边界推导规则

分区采用半开区间 `[下界, 上界)`。单个谓词推导为取值域：

- `k = v`  -> `[v, v+1)`
- `k > v`  -> `[v+1, +inf)`；`k >= v` -> `[v, +inf)`
- `k < v`  -> `(-inf, v)`；`k <= v` -> `(-inf, v+1)`
- `k != v` -> `(-inf, v) ∪ [v+1, +inf)`（只去掉单点，含该点的分区仍保留）
- `k IN (v1..vn)` -> 各 `[vi, vi+1)` 的并集（去重、合并相邻区间）
- 多个谓词之间取交集（AND）；域与每个分区区间求交，只保留命中分区
- 两侧分区键比较对分区对做可行性过滤：
  - `=`：两分区区间有交集（同方案下只保留相同分区 id 的对角对）
  - `<`：左区间最小值 `<` 右区间最大值；`<=`：左最小 `<=` 右最大
  - `>`：左区间最大值 `>` 右区间最小值；`>=`：左最大 `>=` 右最小
  - `<>`：除非两区间是同一个单点区间，否则保留

### 兜底策略

以下情况退回全分区扫描，并在 `PruningPlan.fallbackReason()` / `JoinStats` 中标注原因：

- 出现无法分析的 `Predicate.custom(...)`（无法保证剪枝正确性）
- 连接条件完全未引用任何一侧分区键（无分区范围可推导）

仅引用非分区键的谓词不触发兜底，作为残余条件在连接时逐行评估。兜底时仍逐行评估完整条件，结果正确。

### 正确性保证

每个被执行的行对都重新评估**完整**原始条件，剪枝只决定“哪些分区对需要扫描”；
`PruningCorrectnessTest` 用固定种子随机生成 60 组数据/条件，与全量笛卡尔积再过滤逐行比对，
并额外断言“被排除的分区对内不存在任何能满足条件的行对”。

### 用法示例

```java
PartitionScheme scheme = PartitionScheme.equalWidth("k", 0, 10, 4); // [0,10)[10,20)[20,30)[30,+inf)
PartitionedTable left  = PartitionedTable.builder("orders", scheme).addRows(rows).build();
PartitionedTable right = PartitionedTable.builder("items",  scheme).addRows(rows).build();

var result = new PartitionJoinExecutor().join(left, right, List.of(
        Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k")),  // 分区键等值
        Predicate.in(Predicate.Ref.right("k"), 15, 25),                   // IN 推导
        Predicate.le(Predicate.Ref.left("amount"), Predicate.Ref.right("amount"))), // 非分区键残余条件
        new PartitionJoinExecutor.ExecutorConfig(4, PairInterceptor.NONE));

System.out.println(result.stats().summary());
// 分区对总数=16, 参与=1, 剪枝率=93.75%, 兜底=false, 输出行=...
```
