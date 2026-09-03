package com.union.control.service;

import java.util.Map;

public interface MemoryStoreService {
    Map<String, Object> memoryRead(String input);
    Map<String, Object> memoryList(String input);
    Map<String, Object> memoryOperation(String input);
    Map<String, Object> memoryWrite(String input);
    Map<String, Object> memoryDelete(String input);
    Map<String, Object> memorySearch(String input);
}
