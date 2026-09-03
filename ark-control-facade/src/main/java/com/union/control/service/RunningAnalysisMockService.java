package com.union.control.service;

import java.util.Map;

public interface RunningAnalysisMockService {
    Map<String, Object> getOrgInfo(String input);
    Map<String, Object> queryBigData(String input);
    Map<String, Object> announceList(String input);
    Map<String, Object> getJiraInfo(String input);
}
