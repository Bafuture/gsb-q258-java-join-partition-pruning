package com.example.gsb.join;

import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.model.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试辅助：快速构造带 {pk, v} 两列的范围分区表。
 */
public final class TestSupport {

    private TestSupport() {
    }

    /**
     * @param rows 每个元素为 {pk, v}
     */
    public static PartitionedTable table(String name, int partitionCount, long width, List<long[]> rows) {
        List<Row> rs = new ArrayList<>(rows.size());
        long id = 1;
        for (long[] r : rows) {
            rs.add(new Row(id++, Map.of("pk", r[0], "v", r[1])));
        }
        return PartitionedTable.ranged(name, "pk", partitionCount, width, rs);
    }

    public static List<long[]> rows(long[]... rows) {
        return List.of(rows);
    }
}
