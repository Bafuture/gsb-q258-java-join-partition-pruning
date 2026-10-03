package com.example.gsb.join.predicate;

/**
 * 支持的比较运算符。
 */
public enum Op {
    EQ("="), NE("<>"), GT(">"), GE(">="), LT("<"), LE("<=");

    private final String symbol;

    Op(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** 在具体值上计算 a OP b。 */
    public boolean test(long a, long b) {
        return switch (this) {
            case EQ -> a == b;
            case NE -> a != b;
            case GT -> a > b;
            case GE -> a >= b;
            case LT -> a < b;
            case LE -> a <= b;
        };
    }

    public boolean isInequality() {
        return this != EQ && this != NE;
    }
}
