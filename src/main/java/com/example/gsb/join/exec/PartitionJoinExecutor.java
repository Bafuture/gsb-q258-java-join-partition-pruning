package com.example.gsb.join.exec;

import com.example.gsb.join.model.Partition;
import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.model.Row;
import com.example.gsb.join.predicate.JoinCondition;
import com.example.gsb.join.prune.PartitionPair;
import com.example.gsb.join.prune.PruneNote;
import com.example.gsb.join.prune.PartitionPruner;
import com.example.gsb.join.prune.PrunePlan;
import com.example.gsb.join.stats.JoinStats;
import com.example.gsb.join.stats.PairStats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分区连接执行器：先剪枝，再让不同分区对并行做嵌套循环连接。
 *
 * <p>并发度由 {@code maxConcurrency} 显式控制（必须为正数）；
 * 只有出现在剪枝计划里的分区对会调用 {@link Partition#scan()}，
 * 被裁剪掉的分区读取计数始终为 0。
 */
public final class PartitionJoinExecutor {

    /** 分区对开始处理前的回调，便于测试并发度。 */
    @FunctionalInterface
    public interface PairHook {
        void onPairStart(PartitionPair pair);
    }

    private static final PairHook NOOP_HOOK = pair -> { };

    private final int maxConcurrency;
    private final PairHook pairHook;

    public PartitionJoinExecutor(int maxConcurrency) {
        this(maxConcurrency, NOOP_HOOK);
    }

    public PartitionJoinExecutor(int maxConcurrency, PairHook pairHook) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("并发度必须为正数: " + maxConcurrency);
        }
        this.maxConcurrency = maxConcurrency;
        this.pairHook = pairHook;
    }

    /**
     * 执行分区连接。
     *
     * @return 匹配结果（按左行、右行 id 排序，保证确定性）与统计
     */
    public JoinResult join(PartitionedTable left, PartitionedTable right, JoinCondition condition) {
        PrunePlan plan = new PartitionPruner().plan(left, right, condition);

        List<PairWork> works = new ArrayList<>(plan.pairs().size());
        int threads = Math.min(maxConcurrency, Math.max(1, plan.pairs().size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory());
        try {
            List<Future<PairWork>> futures = new ArrayList<>(plan.pairs().size());
            for (PartitionPair pair : plan.pairs()) {
                futures.add(pool.submit(() -> processPair(left, right, condition, pair)));
            }
            for (Future<PairWork> future : futures) {
                try {
                    works.add(future.get());
                } catch (Exception e) {
                    throw new IllegalStateException("分区对连接失败", e);
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }

        works.sort(Comparator.comparingInt((PairWork w) -> w.pair().leftIndex())
                .thenComparingInt(w -> w.pair().rightIndex()));
        List<MatchedRow> all = new ArrayList<>();
        List<PairStats> pairStats = new ArrayList<>(works.size());
        long totalMatched = 0;
        long totalComparisons = 0;
        for (PairWork work : works) {
            all.addAll(work.matched());
            pairStats.add(work.stats());
            totalMatched += work.stats().matched();
            totalComparisons += work.stats().comparisons();
        }
        all.sort(Comparator.comparingLong((MatchedRow m) -> m.left().id()).thenComparingLong(m -> m.right().id()));

        JoinStats stats = new JoinStats(
                plan.totalPairs(),
                plan.participatingPairs(),
                plan.pruneRate(),
                plan.status(),
                plan.fallbackReasons().stream().map(PruneNote::message).toList(),
                totalMatched,
                totalComparisons,
                pairStats);
        return new JoinResult(all, plan, stats);
    }

    private PairWork processPair(PartitionedTable left, PartitionedTable right, JoinCondition condition,
                                 PartitionPair pair) {
        pairHook.onPairStart(pair);
        long start = System.nanoTime();
        Partition lp = left.partitions().get(pair.leftIndex());
        Partition rp = right.partitions().get(pair.rightIndex());
        List<Row> leftRows = lp.scan();
        List<Row> rightRows = rp.scan();
        long comparisons = 0;
        List<MatchedRow> matched = new ArrayList<>();
        for (Row l : leftRows) {
            for (Row r : rightRows) {
                comparisons++;
                if (condition.test(l, r)) {
                    matched.add(new MatchedRow(l, r));
                }
            }
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        PairStats stats = new PairStats(pair.toString(), leftRows.size(), rightRows.size(), comparisons,
                matched.size(), Thread.currentThread().getName(), elapsed);
        return new PairWork(pair, matched, stats);
    }

    private record PairWork(PartitionPair pair, List<MatchedRow> matched, PairStats stats) {
    }

    private static final class ThreadFactory implements java.util.concurrent.ThreadFactory {
        private static final AtomicInteger POOL_SEQ = new AtomicInteger();
        private final AtomicInteger threadSeq = new AtomicInteger();
        private final int poolId = POOL_SEQ.incrementAndGet();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "partition-join-p" + poolId + "-t" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
