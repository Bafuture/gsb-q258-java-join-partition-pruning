package com.example.gsb.join;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按 {@link PartitionScheme} 物理分区的表。构造时按分区键把行分发到各分区。
 */
public final class PartitionedTable {

    private final String name;
    private final PartitionScheme scheme;
    private final List<List<Row>> partitions;

    private PartitionedTable(String name, PartitionScheme scheme, List<List<Row>> partitions) {
        this.name = name;
        this.scheme = scheme;
        this.partitions = partitions;
    }

    public static Builder builder(String name, PartitionScheme scheme) {
        return new Builder(name, scheme);
    }

    public String name() {
        return name;
    }

    public PartitionScheme scheme() {
        return scheme;
    }

    public String keyColumn() {
        return scheme.keyColumn();
    }

    public int partitionCount() {
        return scheme.partitionCount();
    }

    public List<Row> partition(int id) {
        return partitions.get(id);
    }

    public long totalRows() {
        long t = 0;
        for (List<Row> p : partitions) {
            t += p.size();
        }
        return t;
    }

    public List<Row> allRows() {
        List<Row> all = new ArrayList<>();
        partitions.forEach(all::addAll);
        return all;
    }

    public static final class Builder {
        private final String name;
        private final PartitionScheme scheme;
        private final List<List<Row>> partitions;

        private Builder(String name, PartitionScheme scheme) {
            this.name = name;
            this.scheme = scheme;
            this.partitions = new ArrayList<>(scheme.partitionCount());
            for (int i = 0; i < scheme.partitionCount(); i++) {
                partitions.add(new ArrayList<>());
            }
        }

        public Builder addRow(Row row) {
            long key = row.getLong(scheme.keyColumn());
            int pid = scheme.partitionOf(key);
            partitions.get(pid).add(row);
            return this;
        }

        public Builder addRows(List<Row> rows) {
            rows.forEach(this::addRow);
            return this;
        }

        public PartitionedTable build() {
            List<List<Row>> immutable = new ArrayList<>(partitions.size());
            for (List<Row> p : partitions) {
                immutable.add(Collections.unmodifiableList(p));
            }
            return new PartitionedTable(name, scheme, List.copyOf(immutable));
        }
    }
}
