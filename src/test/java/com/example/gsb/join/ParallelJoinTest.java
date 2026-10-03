package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 分区对并行连接测试：并发度控制、确实使用多线程、并行结果与串行结果一致。
 */
class ParallelJoinTest {

    private final PartitionJoinExecutor executor = new PartitionJoinExecutor();

    private PartitionedTable bigTable(String name, PartitionScheme scheme, Random rnd) {
        PartitionedTable.Builder b = PartitionedTable.builder(name, scheme);
        long maxKey = scheme.lowerBound(scheme.partitionCount() - 1) + 30;
        for (int i = 0; i < 200; i++) {
            b.addRow(Row.of("k", Math.floorMod(rnd.nextLong(), maxKey), "v", rnd.nextInt(6)));
        }
        return b.build();
    }

    private Set<String> canonical(List<Row> rows) {
        Set<String> set = new TreeSet<>();
        rows.forEach(r -> set.add(r.asMap().toString()));
        return set;
    }

    @Test
    void parallelResultEqualsSerialResult() {
        Random rnd = new Random(42L);
        PartitionScheme scheme = PartitionScheme.equalWidth("k", 0, 4, 8);
        PartitionedTable left = bigTable("L", scheme, rnd);
        PartitionedTable right = bigTable("R", scheme, rnd);
        List<Predicate> predicates = List.of(
                Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k")),
                Predicate.ge(Predicate.Ref.left("v"), Predicate.Ref.right("v")));

        PartitionJoinExecutor.JoinResult serial = executor.join(
                left, right, predicates, PartitionJoinExecutor.ExecutorConfig.serial());
        PartitionJoinExecutor.JoinResult parallel = executor.join(
                left, right, predicates, PartitionJoinExecutor.ExecutorConfig.ofParallelism(4));

        assertThat(canonical(parallel.rows())).isEqualTo(canonical(serial.rows()));
        assertThat(parallel.rows()).isNotEmpty();
        assertThat(parallel.stats().scannedPairs()).isEqualTo(serial.stats().scannedPairs());
    }

    @Test
    void parallelismIsCappedAndMultipleThreadsAreUsed() throws InterruptedException {
        PartitionScheme scheme = PartitionScheme.equalWidth("k", 0, 100, 8);
        PartitionedTable.Builder lb = PartitionedTable.builder("L", scheme);
        PartitionedTable.Builder rb = PartitionedTable.builder("R", scheme);
        for (long k = 50; k < 800; k++) {
            lb.addRow(Row.of("k", k, "v", k));
            rb.addRow(Row.of("k", k, "v", k));
        }
        PartitionedTable left = lb.build();
        PartitionedTable right = rb.build();

        int parallelism = 3;
        CountDownLatch started = new CountDownLatch(parallelism);
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger current = new AtomicInteger();
        Set<String> workerNames = ConcurrentHashMap.newKeySet();
        Object block = new Object();

        PartitionJoinExecutor.PairInterceptor interceptor = new PartitionJoinExecutor.PairInterceptor() {
            @Override
            public void beforePair(int lp, int rp) {
                workerNames.add(Thread.currentThread().getName());
                int n = current.incrementAndGet();
                maxConcurrent.accumulateAndGet(n, Math::max);
                started.countDown();
                synchronized (block) {
                    try {
                        // 让后到的分区对任务短暂等待，制造并发重叠窗口
                        block.wait(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void afterPair(int lp, int rp) {
                current.decrementAndGet();
                synchronized (block) {
                    block.notifyAll();
                }
            }
        };

        PartitionJoinExecutor.JoinResult result = executor.join(left, right,
                List.of(Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k"))),
                new PartitionJoinExecutor.ExecutorConfig(parallelism, interceptor));

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get())
                .as("观测到的同时在跑的分区对任务数上限应为并发度 %d", parallelism)
                .isLessThanOrEqualTo(parallelism)
                .isGreaterThan(1);
        assertThat(workerNames).as("确实使用了多个工作线程").hasSizeGreaterThanOrEqualTo(parallelism);
        assertThat(result.rows()).isNotEmpty();
    }

    @Test
    void serialConfigRunsOnCallingThread() {
        PartitionScheme scheme = PartitionScheme.equalWidth("k", 0, 10, 3);
        PartitionedTable left = PartitionedTable.builder("L", scheme)
                .addRow(Row.of("k", 5, "v", 1)).build();
        PartitionedTable right = PartitionedTable.builder("R", scheme)
                .addRow(Row.of("k", 5, "v", 1)).build();
        String caller = Thread.currentThread().getName();
        List<String> execThreads = Collections.synchronizedList(new ArrayList<>());
        PartitionJoinExecutor.PairInterceptor interceptor = new PartitionJoinExecutor.PairInterceptor() {
            @Override
            public void beforePair(int lp, int rp) {
                execThreads.add(Thread.currentThread().getName());
            }
        };
        executor.join(left, right,
                List.of(Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k"))),
                new PartitionJoinExecutor.ExecutorConfig(1, interceptor));
        assertThat(execThreads).containsOnly(caller);
    }

    @Test
    void invalidParallelismIsRejected() {
        assertThatThrownBy(() -> PartitionJoinExecutor.ExecutorConfig.ofParallelism(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
