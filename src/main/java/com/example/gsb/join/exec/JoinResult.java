package com.example.gsb.join.exec;

import com.example.gsb.join.prune.PrunePlan;
import com.example.gsb.join.stats.JoinStats;
import java.util.List;

/**
 * 一次分区连接的产物：匹配结果、剪枝计划与统计。
 */
public record JoinResult(List<MatchedRow> matchedRows, PrunePlan prunePlan, JoinStats stats) {

    public JoinResult {
        matchedRows = List.copyOf(matchedRows);
    }
}
