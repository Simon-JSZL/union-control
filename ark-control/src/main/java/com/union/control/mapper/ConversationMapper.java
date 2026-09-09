package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ConversationMapper {
    List<Map<String, Object>> findConversations(@Param("userId") String userId,
                                                @Param("limit") int limit);
    Map<String, Object> findConversation(@Param("userId") String userId,
                                         @Param("conversationId") String conversationId);
    int updateConversationTitle(@Param("title") String title,
                                @Param("conversationId") String conversationId,
                                @Param("userId") String userId);
    int softDeleteMessages(@Param("conversationId") String conversationId,
                           @Param("userId") String userId);
    int softDeleteConversation(@Param("conversationId") String conversationId,
                               @Param("userId") String userId);
    int insertConversation(@Param("conversationId") String conversationId,
                           @Param("userId") String userId,
                           @Param("title") String title);
    Long currentMessageSequence(@Param("conversationId") String conversationId);
    int insertMessage(@Param("conversationId") String conversationId,
                      @Param("userId") String userId,
                      @Param("messageId") String messageId,
                      @Param("executionId") Long executionId,
                      @Param("role") String role,
                      @Param("sequence") long sequence,
                      @Param("payload") String payload);
    int touchConversation(@Param("conversationId") String conversationId,
                          @Param("userId") String userId);
    List<Map<String, Object>> findRootMessages(@Param("userId") String userId,
                                               @Param("conversationId") String conversationId);
    List<Map<String, Object>> findBrowserMessages(@Param("userId") String userId,
                                                  @Param("conversationId") String conversationId);
    Long requireOwnedActive(@Param("conversationId") String conversationId,
                            @Param("userId") String userId);
    Long requireOwned(@Param("conversationId") String conversationId,
                      @Param("userId") String userId,
                      @Param("lock") boolean lock);
}
