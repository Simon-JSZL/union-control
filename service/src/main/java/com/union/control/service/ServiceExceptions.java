package com.union.control.service;

/** Shared application exceptions translated by the web layer. */
public final class ServiceExceptions {
    private ServiceExceptions() {}

    public static final class ActiveExecutionException extends RuntimeException {}
    public static final class StaleExecutionException extends RuntimeException {}
    public static final class UnauthorizedException extends RuntimeException {}

    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static final class DataCorruptionException extends IllegalStateException {}
}
