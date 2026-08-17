package com.union.control.sensitive.interceptor;

import com.union.control.sensitive.demo.SensitiveDemoRecord;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SensitiveAesInterceptorTest {
    @Test
    public void encryptsOnlyAtTheDatabaseWriteBoundaryAndRestoresTheCallerValue() throws Throwable {
        RecordingCrypto crypto = new RecordingCrypto();
        RecordingHook hook = new RecordingHook();
        SensitiveAesInterceptor interceptor = new SensitiveAesInterceptor(crypto, hook);
        SensitiveDemoRecord record = new SensitiveDemoRecord("user-1", "13800138000");
        Executor executor = mock(Executor.class);
        when(executor.update(any(MappedStatement.class), any())).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock call) {
                SensitiveDemoRecord bound = (SensitiveDemoRecord) call.getArguments()[1];
                assertEquals("encrypted:13800138000", bound.getContent());
                return 1;
            }
        });

        Object result = interceptor.intercept(invocation(executor, "update",
                new Class<?>[]{MappedStatement.class, Object.class},
                statement(SensitiveAesInterceptor.INSERT_STATEMENT, SqlCommandType.INSERT), record));

        assertEquals(1, result);
        assertEquals("13800138000", record.getContent());
        assertEquals(0, hook.calls);
    }

    @Test
    public void decryptsAtTheDatabaseReadBoundaryThenEntersTheRevealBypass() throws Throwable {
        RecordingCrypto crypto = new RecordingCrypto();
        RecordingHook hook = new RecordingHook();
        SensitiveAesInterceptor interceptor = new SensitiveAesInterceptor(crypto, hook);
        SensitiveDemoRecord record = new SensitiveDemoRecord();
        record.setContent("encrypted:13800138000");
        List<SensitiveDemoRecord> rows = Collections.singletonList(record);
        Executor executor = mock(Executor.class);
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class), any(ResultHandler.class)))
                .thenReturn((List) rows);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", "user-1");

        Object result = interceptor.intercept(invocation(executor, "query",
                new Class<?>[]{MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class},
                statement(SensitiveAesInterceptor.LIST_STATEMENT, SqlCommandType.SELECT), parameters,
                RowBounds.DEFAULT, null));

        SensitiveDemoRecord displayed = (SensitiveDemoRecord) ((List<?>) result).get(0);
        assertEquals("[#masked#VIEW:rt_test]", displayed.getContent());
        assertEquals("encrypted:13800138000", record.getContent());
        assertEquals("user-1", hook.userId);
        assertEquals("encrypted:13800138000", hook.ciphertext);
        assertEquals("13800138000", hook.plaintext);
    }

    private static Invocation invocation(Object target, String method, Class<?>[] types, Object... args)
            throws Exception {
        Method targetMethod = Executor.class.getMethod(method, types);
        return new Invocation(target, targetMethod, args);
    }

    private static MappedStatement statement(String id, SqlCommandType commandType) {
        Configuration configuration = new Configuration();
        return new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, "SELECT 1"), commandType).build();
    }

    private static class RecordingCrypto implements SensitiveCrypto {
        @Override
        public String encrypt(String plaintext) {
            return "encrypted:" + plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return ciphertext.substring("encrypted:".length());
        }
    }

    private static class RecordingHook implements DecryptedValueHook {
        int calls;
        String userId;
        String ciphertext;
        String plaintext;

        @Override
        public List<String> afterDecrypt(String userId, List<DecryptedValueHook.Value> values) {
            calls++;
            this.userId = userId;
            this.ciphertext = values.get(0).getCiphertext();
            this.plaintext = values.get(0).getPlaintext();
            return Collections.singletonList("[#masked#VIEW:rt_test]");
        }
    }
}
