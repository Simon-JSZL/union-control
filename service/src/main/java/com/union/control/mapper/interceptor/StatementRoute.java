package com.union.control.mapper.interceptor;

enum StatementRoute {
    PASSTHROUGH(false),
    ENCRYPT(false),
    DECRYPT(true),
    ADDRESS_BOOK(true);

    private final boolean sensitiveResult;

    StatementRoute(boolean sensitiveResult) {
        this.sensitiveResult = sensitiveResult;
    }

    boolean hasSensitiveResult() {
        return sensitiveResult;
    }
}
