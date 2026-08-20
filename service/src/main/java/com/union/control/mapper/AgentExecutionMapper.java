package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AgentExecutionMapper {
    int softDeleteExecutions(@Param("conversationId") String conversationId,
                             @Param("userId") String userId);
    int insertRootExecution(@Param("runId") String runId,
                            @Param("conversationId") String conversationId,
                            @Param("userId") String userId);
    int insertCompletedRootExecution(Map<String, Object> execution);
    int updateRootAgent(@Param("id") Long id,
                        @Param("agentName") String agentName);
    int insertChildExecution(@Param("runId") String runId,
                             @Param("conversationId") String conversationId,
                             @Param("userId") String userId,
                             @Param("parentExecutionId") Long parentExecutionId,
                             @Param("agentName") String agentName,
                             @Param("delegationCallId") String delegationCallId,
                             @Param("task") String task);
    List<Map<String, Object>> findExecutions(@Param("userId") String userId,
                                             @Param("conversationId") String conversationId);
    List<Map<String, Object>> findExecution(@Param("userId") String userId,
                                            @Param("conversationId") String conversationId,
                                            @Param("runId") String runId,
                                            @Param("lock") boolean lock);
    int finishExecution(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("errorCode") String errorCode);
}
