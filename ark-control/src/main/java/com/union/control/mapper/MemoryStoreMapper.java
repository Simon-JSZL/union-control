package com.union.control.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface MemoryStoreMapper {
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
}
