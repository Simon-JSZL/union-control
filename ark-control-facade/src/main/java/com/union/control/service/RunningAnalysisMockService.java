package com.union.control.service;

import java.util.Map;

public interface RunningAnalysisMockService {
    Map<String, Object> getOrgInfo(String input);
    Map<String, Object> queryBigData(String input);
    Map<String, Object> queryChanges(String input);
    Map<String, Object> queryFaults(String input);

    /** 可跨 Dubbo 传递的有界错误；不携带原始请求或业务内容。 */
    class QueryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final int status;

        public QueryException(String code, int status) {
            super(code);
            this.code = code;
            this.status = status;
        }

        public String getCode() { return code; }
        public int getStatus() { return status; }
    }
}
