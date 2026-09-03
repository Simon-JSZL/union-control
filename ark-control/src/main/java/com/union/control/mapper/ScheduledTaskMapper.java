package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ScheduledTaskMapper {
    List<Long> lockUserTasks(@Param("userId") String userId);

    int countRunnableTasks(@Param("userId") String userId);

    int insertTask(Map<String, Object> task);

    int updateTask(Map<String, Object> task);

    long countTasks(@Param("userId") String userId,
                    @Param("keyword") String keyword,
                    @Param("status") String status);

    List<Map<String, Object>> findTasks(@Param("userId") String userId,
                                        @Param("keyword") String keyword,
                                        @Param("status") String status,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    Map<String, Object> findTaskDetail(@Param("taskId") long taskId,
                                       @Param("userId") String userId);

    Map<String, Object> findTaskOwner(@Param("taskId") long taskId,
                                      @Param("userId") String userId,
                                      @Param("lock") boolean lock);

    long countRuns(@Param("taskId") long taskId, @Param("userId") String userId);

    List<Map<String, Object>> findRuns(@Param("taskId") long taskId,
                                       @Param("userId") String userId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    Map<String, Object> findOwnedRun(@Param("runId") long runId,
                                     @Param("userId") String userId,
                                     @Param("lock") boolean lock);

    long countUnread(@Param("userId") String userId);

    List<Map<String, Object>> findUnread(@Param("userId") String userId);

    int activateTask(@Param("taskId") long taskId,
                     @Param("userId") String userId,
                     @Param("nextRunAt") String nextRunAt);

    int pauseTask(@Param("taskId") long taskId, @Param("userId") String userId);

    int discardTask(@Param("taskId") long taskId, @Param("userId") String userId);

    Map<String, Object> findDueTask();

    int insertRun(Map<String, Object> run);

    int completeOnceTask(@Param("taskId") long taskId);

    int updateNextRun(@Param("taskId") long taskId,
                      @Param("nextRunAt") String nextRunAt);

    List<Long> findPendingRunIds(@Param("limit") int limit);

    int beginRun(@Param("runId") long runId,
                 @Param("tokenHash") String tokenHash,
                 @Param("tokenExpiresAt") String tokenExpiresAt);

    Map<String, Object> findPendingRunContext(@Param("runId") long runId);

    Map<String, Object> resolveScheduledToken(@Param("tokenHash") String tokenHash);

    Map<String, Object> findRunForUpdate(@Param("runId") long runId);

    int completeRun(@Param("runId") long runId, @Param("resultPayload") String resultPayload);

    int failRun(@Param("runId") long runId,
                @Param("errorCode") String errorCode,
                @Param("errorMessage") String errorMessage);

    int failStaleRuns(@Param("maxRunSeconds") int maxRunSeconds);

    int markRunRead(@Param("runId") long runId);

    int attachConversation(@Param("runId") long runId,
                           @Param("conversationId") String conversationId);

    int softDeleteByConversation(@Param("conversationId") String conversationId,
                                 @Param("userId") String userId);

}
