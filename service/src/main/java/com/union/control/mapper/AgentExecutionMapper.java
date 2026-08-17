package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AgentExecutionMapper {
    void cleanupStaleExecutionChildren(
            @Param("executionMaxAgeSeconds") int executionMaxAgeSeconds,
            @Param("cancelRequestMaxAgeSeconds") int cancelRequestMaxAgeSeconds);
    void cleanupStaleExecutionRoots(
            @Param("executionMaxAgeSeconds") int executionMaxAgeSeconds,
            @Param("cancelRequestMaxAgeSeconds") int cancelRequestMaxAgeSeconds);
    int softDeleteExecutions(@Param("conversationId") String conversationId,
                             @Param("userId") String userId);
    int insertRootExecution(@Param("runId") String runId,
                            @Param("conversationId") String conversationId,
                            @Param("userId") String userId);
    int insertCompletedRootExecution(Map<String, Object> execution);
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
}
