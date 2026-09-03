package com.union.control.service;

public interface AgentProxyService {
    AgentResponse sync(String cookie, String payload);
    AgentResponse scheduled(String authorization);
    AgentResponse cancel(String cookie, String conversationId, String runId);
}
