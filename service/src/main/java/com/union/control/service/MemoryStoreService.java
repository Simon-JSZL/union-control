package com.union.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.MemoryStoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.union.control.utils.ServiceSupport.*;

@Service
public class MemoryStoreService {
    private static final Pattern MEMORY_PATH =
            Pattern.compile("[A-Za-z0-9._:@+-]+(?:/[A-Za-z0-9._:@+-]+)*");

    private final MemoryStoreMapper mapper;
    private final ObjectMapper json;

    public MemoryStoreService(MemoryStoreMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public Map<String, Object> memoryRead(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String path = memoryPath(userId, payload, "path");
        int maxChars = integer(payload, "maxChars", 1, 65536);
        List<Map<String, Object>> rows = mapper.readMemory(userId, path);
        Map<String, Object> response = success();
        if (rows.isEmpty()) {
            response.put("file", null);
            return response;
        }
        Map<String, Object> row = rows.get(0);
        String content = String.valueOf(row.get("content"));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("content", content.substring(0, Math.min(content.length(), maxChars)));
        file.put("version", String.valueOf(row.get("version")));
        file.put("operationId", row.get("operationId"));
        file.put("truncated", content.length() > maxChars);
        response.put("file", file);
        return response;
    }

    public Map<String, Object> memoryList(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String prefix = memoryPrefix(userId, payload);
        int limit = integer(payload, "limit", 1, 1000);
        List<String> paths = mapper.listMemoryPaths(userId, likePrefix(prefix), limit);
        Map<String, Object> response = success();
        response.put("paths", paths);
        return response;
    }

    public Map<String, Object> memoryOperation(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String operationId = text(payload, "operationId", 128, true);
        String fingerprint = text(payload, "fingerprint", 128, true);
        List<Map<String, Object>> rows = mapper.findMemoryOperation(userId, operationId, false);
        if (rows.isEmpty()) {
            Map<String, Object> response = success();
            response.put("mutation", null);
            return response;
        }
        return replayResponse(rows.get(0), fingerprint);
    }

    @Transactional
    public Map<String, Object> memoryWrite(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String path = memoryPath(userId, payload, "path");
        String content = text(payload, "content", 65536, true);
        String expected = optionalText(payload, "expectedVersion", 32);
        Operation operation = operation(payload);
        Map<String, Object> replay = replay(userId, operation);
        if (replay != null) return replay;
        List<Map<String, Object>> rows = memoryRow(userId, path);
        String currentVersion = rows.isEmpty() ? null : String.valueOf(rows.get(0).get("version"));
        if (!same(currentVersion, expected))
            return memoryError("version_conflict", "memory version 已变化");
        boolean existed = !rows.isEmpty();
        Long prior = mapper.currentMemoryVersion(userId, path);
        long version = (prior == null ? 0 : prior) + 1;
        if (existed) {
            mapper.updateMemoryFile(content, version, operation == null ? null : operation.id,
                    rows.get(0).get("id"));
        } else {
            mapper.insertMemoryFile(userId, path, content, version,
                    operation == null ? null : operation.id);
        }
        saveReceipt(userId, operation, version, existed);
        Map<String, Object> response = success();
        response.put("mutation", mutation(version, false, existed));
        return response;
    }

    @Transactional
    public Map<String, Object> memoryDelete(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String path = memoryPath(userId, payload, "path");
        String expected = optionalText(payload, "expectedVersion", 32);
        Operation operation = operation(payload);
        Map<String, Object> replay = replay(userId, operation);
        if (replay != null) return replay;
        List<Map<String, Object>> rows = memoryRow(userId, path);
        String currentVersion = rows.isEmpty() ? null : String.valueOf(rows.get(0).get("version"));
        if (!same(currentVersion, expected))
            return memoryError("version_conflict", "memory version 已变化");
        boolean existed = !rows.isEmpty();
        if (existed) {
            mapper.softDeleteMemoryFile(rows.get(0).get("id"));
        }
        saveReceipt(userId, operation, null, existed);
        Map<String, Object> response = success();
        response.put("mutation", mutation(null, false, existed));
        return response;
    }

    public Map<String, Object> memorySearch(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String prefix = memoryPrefix(userId, payload);
        String query = text(payload, "query", 512, true);
        int limit = integer(payload, "limit", 1, 100);
        int maxFiles = integer(payload, "maxFiles", 1, 1000);
        int maxChars = integer(payload, "maxChars", 1, 100000);
        int maxFileChars = integer(payload, "maxFileChars", 1, 65536);
        List<Map<String, Object>> rows = mapper.searchMemory(
                userId, likePrefix(prefix), maxFiles + 1);
        boolean truncated = rows.size() > maxFiles;
        List<Map<String, Object>> matches = new ArrayList<>();
        int remaining = maxChars;
        int scanned = Math.min(rows.size(), maxFiles);
        String lowerQuery = query.toLowerCase();
        for (int index = 0; index < scanned && matches.size() < limit && remaining > 0; index++) {
            Map<String, Object> row = rows.get(index);
            String path = String.valueOf(row.get("path"));
            String content = String.valueOf(row.get("content"));
            String bounded = content.substring(0, Math.min(content.length(), maxFileChars));
            if (!path.toLowerCase().contains(lowerQuery) && !bounded.toLowerCase().contains(lowerQuery))
                continue;
            String snippet = bounded.substring(0, Math.min(bounded.length(), remaining));
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("path", path);
            match.put("snippet", snippet);
            match.put("score", 1.0d);
            matches.add(match);
            remaining -= path.length() + snippet.length();
            if (content.length() > maxFileChars) truncated = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matches);
        result.put("scanned", scanned);
        result.put("truncated", truncated);
        Map<String, Object> response = success();
        response.put("result", result);
        return response;
    }

    private List<Map<String, Object>> memoryRow(String userId, String path) {
        return mapper.findMemoryRow(userId, path);
    }

    private Map<String, Object> replay(String userId, Operation operation) {
        if (operation == null) return null;
        List<Map<String, Object>> rows = mapper.findMemoryOperation(userId, operation.id, true);
        if (rows.isEmpty()) return null;
        return replayResponse(rows.get(0), operation.fingerprint);
    }

    private Map<String, Object> replayResponse(
            Map<String, Object> row, String fingerprint) {
        if (!fingerprint.equals(String.valueOf(row.get("fingerprint"))))
            return memoryError("operation_conflict", "operation id 已被不同参数使用");
        Map<String, Object> response = success();
        response.put("mutation", mutation(row.get("resultVersion"), true,
                number(row.get("existed")) != 0));
        return response;
    }

    private void saveReceipt(String userId, Operation operation, Long version, boolean existed) {
        if (operation == null) return;
        mapper.insertMemoryOperation(userId, operation.id, operation.fingerprint, version,
                existed ? 1 : 0);
    }

    private Operation operation(Map<String, Object> payload) {
        Object raw = payload.get("operation");
        if (raw == null) return null;
        if (!(raw instanceof Map)) throw new IllegalArgumentException("operation 非法");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) raw;
        return new Operation(text(value, "id", 128, true),
                text(value, "fingerprint", 128, true));
    }

    private String memoryPath(String userId, Map<String, Object> payload, String key) {
        String path = text(payload, key, 512, true);
        if (!MEMORY_PATH.matcher(path).matches() || path.contains("..") ||
                !path.startsWith(userId + "/personal/"))
            throw new IllegalArgumentException("memory path 非法");
        return path;
    }

    private String memoryPrefix(String userId, Map<String, Object> payload) {
        String prefix = optionalText(payload, "prefix", 512);
        if (prefix == null) prefix = "";
        if (prefix.isEmpty()) prefix = userId + "/personal/";
        if (!prefix.startsWith(userId + "/personal/") || prefix.contains(".."))
            throw new IllegalArgumentException("memory prefix 非法");
        return prefix;
    }


    private static Map<String, Object> mutation(
            Object version, boolean replayed, boolean existed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version == null ? null : String.valueOf(version));
        result.put("replayed", replayed);
        result.put("existed", existed);
        return result;
    }

    private static Map<String, Object> memoryError(String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("errorCode", code);
        response.put("errorMsg", message);
        return response;
    }

    private static final class Operation {
        private final String id;
        private final String fingerprint;

        private Operation(String id, String fingerprint) {
            this.id = id;
            this.fingerprint = fingerprint;
        }
    }
}
