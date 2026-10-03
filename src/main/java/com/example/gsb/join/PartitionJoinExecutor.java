package com.example.gsb.join;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分区表连接执行器：先剪枝得到分区对计划，再按分区对并行执行嵌套循环连接。
 *
 * <p>并发度由 {@link ExecutorConfig#parallelism()} 控制（固定线程池，1 表示串行）。
 * 每个分区对是一个独立任务，不同分区对之间无共享状态，可安全并行。
 * 结果按 (左分区, 右分区, 行序号) 排序，保证多次执行输出确定。
 */
public final class PartitionJoinExecutor {

    /** 测试/观测用钩子：每个分区对任务开始/结束时回调（在执行线程上调用）。 */
    public interface PairInterceptor {
        PairInterceptor NONE = new PairInterceptor() {
        };

        default void beforePair(int leftPartition, int rightPartition) {
        }

        default void afterPair(int leftPartition, int rightPartition) {
        }
    }

    /** @param parallelism 并发度（>=1）；@param interceptor 分区对执行钩子 */
    public record ExecutorConfig(int parallelism, PairInterceptor interceptor) {
        public ExecutorConfig {
            if (parallelism < 1) {
                throw new IllegalArgumentException("并发度必须 >= 1");
            }
            if (interceptor == null) {
                interceptor = PairInterceptor.NONE;
            }
        }

        public static ExecutorConfig ofParallelism(int parallelism) {
            return new ExecutorConfig(parallelism, PairInterceptor.NONE);
        }

        public static ExecutorConfig serial() {
            return ofParallelism(1);
        }
    }

    /** 连接执行结果：输出行 + 剪枝计划 + 统计。 */
    public record JoinResult(List<Row> rows,
                             PartitionPruner.PruningPlan plan,
                             JoinStats stats) {
    }

    private final PartitionPruner pruner = new PartitionPruner();

    public JoinResult join(PartitionedTable left,
                           PartitionedTable right,
                           List<Predicate> predicates,
                           ExecutorConfig config) {
        PartitionPruner.PruningPlan plan = pruner.prune(left, right, predicates);
        List<PartitionPruner.PairPlan> pairs = plan.pairs();

        List<JoinStats.PairStat> pairStats = new ArrayList<>(pairs.size());
        List<Row> rows = new ArrayList<>();
        if (!pairs.isEmpty()) {
            List<PairOutcome> outcomes = runPairs(left, right, predicates, pairs, config);
            for (PairOutcome o : outcomes) {
                pairStats.add(o.stat());
                rows.addAll(o.rows());
            }
        }

        double pruneRate = plan.totalPairs() == 0
                ? 0.0
                : 1.0 - (double) pairs.size() / plan.totalPairs();
        JoinStats stats = new JoinStats(
                plan.totalPairs(), pairs.size(), pruneRate,
                plan.fallback(), plan.fallbackReason(),
                List.copyOf(pairStats), rows.size());
        return new JoinResult(List.copyOf(rows), plan, stats);
    }

    private record PairOutcome(JoinStats.PairStat stat, List<Row> rows) {
    }

    private List<PairOutcome> runPairs(PartitionedTable left,
                                       PartitionedTable right,
                                       List<Predicate> predicates,
                                       List<PartitionPruner.PairPlan> pairs,
                                       ExecutorConfig config) {
        List<Callable<PairOutcome>> tasks = new ArrayList<>(pairs.size());
        for (PartitionPruner.PairPlan pair : pairs) {
            tasks.add(() -> runPair(left, right, predicates, pair, config.interceptor()));
        }

        List<PairOutcome> outcomes = new ArrayList<>(pairs.size());
        if (config.parallelism() == 1) {
            for (Callable<PairOutcome> task : tasks) {
                try {
                    outcomes.add(task.call());
                } catch (Exception e) {
                    throw new IllegalStateException("分区对执行失败", e);
                }
            }
            return outcomes;
        }

        AtomicInteger threadSeq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "partition-join-worker-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(config.parallelism(), factory);
        try {
            List<Future<PairOutcome>> futures = new ArrayList<>(tasks.size());
            for (Callable<PairOutcome> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<PairOutcome> f : futures) {
                outcomes.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("分区对并行执行被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("分区对执行失败", e.getCause());
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    private PairOutcome runPair(PartitionedTable left,
                                PartitionedTable right,
                                List<Predicate> predicates,
                                PartitionPruner.PairPlan pair,
                                PairInterceptor interceptor) {
        interceptor.beforePair(pair.leftPartition(), pair.rightPartition());
        try {
            List<Row> leftRows = left.partition(pair.leftPartition());
            List<Row> rightRows = right.partition(pair.rightPartition());
            ConcurrentLinkedQueue<Row> matched = new ConcurrentLinkedQueue<>();
            long evaluated = 0;
            for (Row l : leftRows) {
                for (Row r : rightRows) {
                    evaluated++;
                    boolean ok = true;
                    for (Predicate p : predicates) {
                        if (!p.test(l, r)) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        matched.add(Row.merged(l, r));
                    }
                }
            }
            List<Row> rows = new ArrayList<>(matched);
            rows.sort(Comparator.comparing((Row row) -> row.asMap().toString()));
            JoinStats.PairStat stat = new JoinStats.PairStat(
                    pair.leftPartition(), pair.rightPartition(),
                    leftRows.size(), rightRows.size(), evaluated, rows.size());
            return new PairOutcome(stat, rows);
        } finally {
            interceptor.afterPair(pair.leftPartition(), pair.rightPartition());
        }
    }
}
