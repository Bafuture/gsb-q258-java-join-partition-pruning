package com.example.gsb.join.prune;

/**
 * 剪枝过程中的一条说明：信息、警告或兜底原因。
 */
public record PruneNote(Level level, Code code, String message) {

    public enum Level {
        INFO, WARN, FALLBACK
    }

    public enum Code {
        /** 连接条件里没有任何与分区键相关的谓词。 */
        NO_PARTITION_KEY_PREDICATE,
        /** 分区键上的跨表比较不是等值（如 l.pk > r.pk），无法做同域配对。 */
        NON_EQUI_PARTITION_KEY_JOIN,
        /** 分区键上的不等谓词（<>）无法推导取值域。 */
        UNSUPPORTED_PARTITION_KEY_OPERATOR,
        /** 分区键谓词只出现在单侧，无法约束另一侧。 */
        PARTIAL_SIDE_RESTRICTION,
        /** 非分区键谓词不参与剪枝，仅作信息提示。 */
        NON_PARTITION_KEY_PREDICATE
    }

    public static PruneNote info(Code code, String message) {
        return new PruneNote(Level.INFO, code, message);
    }

    public static PruneNote warn(Code code, String message) {
        return new PruneNote(Level.WARN, code, message);
    }

    public static PruneNote fallback(Code code, String message) {
        return new PruneNote(Level.FALLBACK, code, message);
    }

    @Override
    public String toString() {
        return "[" + level + "/" + code + "] " + message;
    }
}
