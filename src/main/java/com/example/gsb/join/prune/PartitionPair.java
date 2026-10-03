package com.example.gsb.join.prune;

/**
 * 一个需要参与连接的分区对（左分区下标, 右分区下标）。
 */
public record PartitionPair(int leftIndex, int rightIndex) {

    @Override
    public String toString() {
        return "(L" + leftIndex + ",R" + rightIndex + ")";
    }
}
