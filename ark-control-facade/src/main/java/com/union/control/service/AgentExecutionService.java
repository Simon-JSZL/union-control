package com.union.control.service;

import java.util.Map;

public interface AgentExecutionService {
    Map<String, Object> claimAguiRun(String payload);
    Map<String, Object> completeRun(String input);
}
