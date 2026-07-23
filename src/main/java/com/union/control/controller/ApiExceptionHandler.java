package com.union.control.controller;

import com.union.control.service.ControlService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice(assignableTypes = {AgentController.class, CommonController.class, LlmController.class})
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ControlService.UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, null, "缺少或无效登录会话");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        return error(HttpStatus.BAD_REQUEST, null, error.getMessage());
    }

    @ExceptionHandler(ControlService.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ControlService.NotFoundException error) {
        return error(HttpStatus.NOT_FOUND, null, error.getMessage());
    }

    @ExceptionHandler(ControlService.ActiveExecutionException.class)
    public ResponseEntity<Map<String, Object>> activeExecution(
            ControlService.ActiveExecutionException ignored) {
        return error(HttpStatus.CONFLICT, "agent_run_active", "已有任务正在执行");
    }

    @ExceptionHandler(ControlService.StaleExecutionException.class)
    public ResponseEntity<Map<String, Object>> staleExecution() {
        return error(HttpStatus.CONFLICT, "agent_execution_stale", "执行已结束或不属于当前请求");
    }

    @ExceptionHandler(ControlService.DataCorruptionException.class)
    public ResponseEntity<Map<String, Object>> dataCorruption() {
        LOG.error("Stored AG-UI message payload is invalid");
        return error(HttpStatus.INTERNAL_SERVER_ERROR, null, "会话数据损坏");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception error) {
        LOG.error("Request failed", error);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, null, "服务处理失败");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return new ResponseEntity<>(body(code, message), status);
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        if (code != null) body.put("errorCode", code);
        body.put("errorMsg", message);
        return body;
    }
}
