package com.union.control.service;

import java.util.Map;

public interface ConversationService {
    Map<String, Object> userInfo(String input);
    Map<String, Object> conversations(String input);
    Map<String, Object> conversation(String input);
    Map<String, Object> conversationMessages(String input);
    Map<String, Object> rename(String input);
    Map<String, Object> deleteConversation(String input);
}
