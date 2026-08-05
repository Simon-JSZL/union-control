package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ControlMapper {
    void cleanupStaleExecutionChildren(
            @Param("executionMaxAgeSeconds") int executionMaxAgeSeconds,
            @Param("cancelRequestMaxAgeSeconds") int cancelRequestMaxAgeSeconds);

    void cleanupStaleExecutionRoots(
            @Param("executionMaxAgeSeconds") int executionMaxAgeSeconds,
            @Param("cancelRequestMaxAgeSeconds") int cancelRequestMaxAgeSeconds);

    List<Map<String, Object>> findConversations(@Param("userId") String userId,
                                                @Param("limit") int limit);

    Map<String, Object> findConversation(@Param("userId") String userId,
                                         @Param("conversationId") String conversationId);

    int updateConversationTitle(@Param("title") String title,
                                @Param("conversationId") String conversationId,
                                @Param("userId") String userId);

    int softDeleteMessages(@Param("conversationId") String conversationId,
                           @Param("userId") String userId);

    int softDeleteExecutions(@Param("conversationId") String conversationId,
                             @Param("userId") String userId);

    int softDeleteConversation(@Param("conversationId") String conversationId,
                               @Param("userId") String userId);

    int insertConversation(@Param("conversationId") String conversationId,
                           @Param("userId") String userId,
                           @Param("title") String title);

    int insertRootExecution(@Param("runId") String runId,
                            @Param("conversationId") String conversationId,
                            @Param("userId") String userId);

    int updateChildrenStatus(@Param("parentExecutionId") Long parentExecutionId,
                             @Param("status") String status,
                             @Param("errorCode") String errorCode);

    int updateRootAgent(@Param("id") Long id,
                        @Param("agentName") String agentName,
                        @Param("currentAgentName") String currentAgentName);

    int insertChildExecution(@Param("runId") String runId,
                             @Param("conversationId") String conversationId,
                             @Param("userId") String userId,
                             @Param("parentExecutionId") Long parentExecutionId,
                             @Param("agentName") String agentName,
                             @Param("delegationCallId") String delegationCallId,
                             @Param("task") String task);

    Long currentMessageSequence(@Param("conversationId") String conversationId);

    int insertMessage(@Param("conversationId") String conversationId,
                      @Param("userId") String userId,
                      @Param("messageId") String messageId,
                      @Param("executionId") Long executionId,
                      @Param("role") String role,
                      @Param("sequence") long sequence,
                      @Param("payload") String payload);

    Map<String, Object> findMessage(@Param("conversationId") String conversationId,
                                    @Param("messageId") String messageId);

    int touchConversation(@Param("conversationId") String conversationId,
                          @Param("userId") String userId);

    List<Map<String, Object>> readMemory(@Param("userId") String userId,
                                         @Param("path") String path);

    List<String> listMemoryPaths(@Param("userId") String userId,
                                 @Param("prefix") String prefix,
                                 @Param("limit") int limit);

    List<Map<String, Object>> findMemoryOperation(@Param("userId") String userId,
                                                  @Param("operationId") String operationId,
                                                  @Param("lock") boolean lock);

    Long currentMemoryVersion(@Param("userId") String userId,
                              @Param("path") String path);

    int updateMemoryFile(@Param("content") String content,
                         @Param("version") long version,
                         @Param("operationId") String operationId,
                         @Param("id") Object id);

    int insertMemoryFile(@Param("userId") String userId,
                         @Param("path") String path,
                         @Param("content") String content,
                         @Param("version") long version,
                         @Param("operationId") String operationId);

    int softDeleteMemoryFile(@Param("id") Object id);

    List<Map<String, Object>> searchMemory(@Param("userId") String userId,
                                           @Param("prefix") String prefix,
                                           @Param("limit") int limit);

    List<Map<String, Object>> findMemoryRow(@Param("userId") String userId,
                                            @Param("path") String path);

    int insertMemoryOperation(@Param("userId") String userId,
                              @Param("operationId") String operationId,
                              @Param("fingerprint") String fingerprint,
                              @Param("version") Long version,
                              @Param("existed") int existed);

    List<Map<String, Object>> findRootMessages(@Param("userId") String userId,
                                               @Param("conversationId") String conversationId);

    List<Map<String, Object>> findBrowserMessages(@Param("userId") String userId,
                                                  @Param("conversationId") String conversationId);

    List<Map<String, Object>> findCurrentRoots(@Param("userId") String userId,
                                               @Param("lock") boolean lock);

    List<Map<String, Object>> findExecutions(@Param("userId") String userId,
                                             @Param("conversationId") String conversationId);

    List<Map<String, Object>> findExecution(@Param("userId") String userId,
                                            @Param("conversationId") String conversationId,
                                            @Param("runId") String runId,
                                            @Param("lock") boolean lock);

    int requestChildrenCancellation(@Param("reason") String reason,
                                    @Param("parentExecutionId") Long parentExecutionId);

    int requestRootCancellation(@Param("reason") String reason,
                                @Param("id") Long id);

    int finishExecution(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("errorCode") String errorCode);

    Long requireOwnedActive(@Param("conversationId") String conversationId,
                            @Param("userId") String userId);

    Long requireOwned(@Param("conversationId") String conversationId,
                      @Param("userId") String userId,
                      @Param("lock") boolean lock);
}
