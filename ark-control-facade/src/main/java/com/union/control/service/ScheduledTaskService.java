package com.union.control.service;

import java.util.List;
import java.util.Map;

public interface ScheduledTaskService {
    Map<String, Object> create(String input);
    Map<String, Object> update(String input);
    Map<String, Object> list(String input);
    Map<String, Object> detail(String input);
    Map<String, Object> runs(String input);
    Map<String, Object> runDetail(String input);
    Map<String, Object> unread(String input);
    Map<String, Object> start(String input);
    Map<String, Object> pause(String input);
    Map<String, Object> discard(String input);
    Map<String, Object> open(String input);
    Long claimDueRun();
    List<Long> pendingRunIds(int limit);
    boolean beginRun(long runId, String tokenHash, String expiresAt);
    void completeFromProxy(long runId, Map<String, Object> result);
    void failRun(long runId, String errorCode, String errorMessage);
    int failStaleRuns(int maxRunSeconds);
    /** Resolves an active run from the raw one-time token; the provider hashes it internally. */
    Map<String, Object> resolveScheduledToken(String rawToken);

    class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public NotFoundException() {}
        public NotFoundException(String message) { super(message); }
    }
    class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ConflictException() {}
        public ConflictException(String message) { super(message); }
    }
    class DataCorruptionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
