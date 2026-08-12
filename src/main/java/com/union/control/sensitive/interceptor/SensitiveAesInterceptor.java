package com.union.control.sensitive.interceptor;

import com.union.control.sensitive.demo.SensitiveDemoRecord;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Local simulation of the existing production database AES interceptor.
 * Encryption/decryption stays on the database path; only the post-decrypt hook is new.
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class SensitiveAesInterceptor implements Interceptor {
    public static final String INSERT_STATEMENT =
            "com.union.control.sensitive.demo.SensitiveDemoMapper.insert";
    public static final String LIST_STATEMENT =
            "com.union.control.sensitive.demo.SensitiveDemoMapper.list";

    private final SensitiveCrypto crypto;
    private final DecryptedValueHook decryptedValueHook;

    public SensitiveAesInterceptor(SensitiveCrypto crypto, DecryptedValueHook decryptedValueHook) {
        this.crypto = crypto;
        this.decryptedValueHook = decryptedValueHook;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] arguments = invocation.getArgs();
        String statementId = ((MappedStatement) arguments[0]).getId();
        if (INSERT_STATEMENT.equals(statementId))
            return encryptForWrite(invocation, (SensitiveDemoRecord) arguments[1]);

        Object result = invocation.proceed();
        if (LIST_STATEMENT.equals(statementId))
            return decryptForRead(result, userId(arguments[1]));
        return result;
    }

    private Object encryptForWrite(Invocation invocation, SensitiveDemoRecord record) throws Throwable {
        String plaintext = record.getContent();
        record.setContent(crypto.encrypt(plaintext));
        try {
            return invocation.proceed();
        } finally {
            record.setContent(plaintext);
        }
    }

    private Object decryptForRead(Object result, String userId) {
        if (!(result instanceof List)) return result;
        List<Object> rewritten = new ArrayList<>(((List<?>) result).size());
        List<SensitiveDemoRecord> records = new ArrayList<>(((List<?>) result).size());
        List<DecryptedValueHook.Value> decrypted = new ArrayList<>(((List<?>) result).size());
        for (Object value : (List<?>) result) {
            if (!(value instanceof SensitiveDemoRecord)) {
                rewritten.add(value);
                continue;
            }
            SensitiveDemoRecord source = (SensitiveDemoRecord) value;
            SensitiveDemoRecord copy = copy(source);
            String ciphertext = source.getContent();
            final String plaintext;
            try {
                plaintext = crypto.decrypt(ciphertext);
            } catch (RuntimeException error) {
                throw new DecryptionException();
            }
            rewritten.add(copy);
            records.add(copy);
            decrypted.add(new DecryptedValueHook.Value(ciphertext, plaintext));
        }
        List<String> displayValues = decryptedValueHook.afterDecrypt(userId, decrypted);
        if (displayValues.size() != records.size()) throw new IllegalStateException("Invalid decrypt hook result");
        for (int i = 0; i < records.size(); i++) records.get(i).setContent(displayValues.get(i));
        return rewritten;
    }

    private static SensitiveDemoRecord copy(SensitiveDemoRecord source) {
        SensitiveDemoRecord copy = new SensitiveDemoRecord();
        copy.setId(source.getId());
        copy.setContent(source.getContent());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private static String userId(Object parameter) {
        Object value = parameter instanceof Map ? ((Map<?, ?>) parameter).get("userId") : null;
        if (!(value instanceof String) || ((String) value).isEmpty())
            throw new IllegalArgumentException("Missing sensitive data owner");
        return (String) value;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {}

    public static class DecryptionException extends RuntimeException {}
}
