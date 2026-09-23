package com.union.control.mapper.interceptor;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.Constant;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import com.nucc.channel.ark.common.util.ResultUtil;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.transaction.Transaction;
import org.junit.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

/** Executes actual MyBatis statement handlers and parameter binding without a database. */
public class SensitiveWriteJdbcTest {
    private final Map savedFlags = new HashMap();
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new AesService());

    @Before public void setup() {
        savedFlags.putAll(Constant.flagMap);
        Constant.flagMap.clear();
        Constant.flagMap.put("interceptorItems", "[]");
        when(redis.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
    }
    @After public void cleanup() { Constant.flagMap.clear(); Constant.flagMap.putAll(savedFlags); }

    @Test public void allExecutorsBindCiphertextOnRepeatedWritesAndKeepGeneratedKeys() throws Exception {
        for (ExecutorType type : ExecutorType.values()) {
            Fixture fixture = new Fixture(type);
            Row row = new Row();
            row.email = "a@example.com";
            for (int i = 0; i < 2; i++) {
                fixture.executor.update(fixture.statement, row);
                assertEquals(type.toString(), "a@example.com", row.email);
            }
            fixture.executor.flushStatements();
            assertEquals(type.toString(), 2, fixture.bound.size());
            for (String bound : fixture.bound) {
                assertNotEquals(row.email, bound);
                assertEquals("a@example.com", crypto.decryptWithCheckNoLog(bound));
            }
            assertNotNull("generated key must remain on the original row: " + type, row.id);
            fixture.executor.close(true);
        }
        verifyZeroInteractions(redis);
    }

    @Test public void expiredOrUnavailableTokensNeverReachJdbc() throws Exception {
        for (ExecutorType type : ExecutorType.values()) {
            for (boolean unavailable : new boolean[]{false, true}) {
                reset(redis);
                if (unavailable) when(redis.get(anyString())).thenThrow(new IllegalStateException("offline"));
                Fixture fixture = new Fixture(type);
                Row row = new Row();
                row.email = "[#a***@example.com#VIEW:rt_" + repeat('A', 43) + "]";
                String original = row.email;
                try {
                    fixture.executor.update(fixture.statement, row);
                    fail("invalid token reached SQL: " + type);
                } catch (IllegalArgumentException expected) {
                    assertEquals(original, row.email);
                }
                assertTrue(fixture.bound.isEmpty());
                verifyZeroInteractions(fixture.connection, fixture.prepared);
                fixture.executor.close(true);
            }
        }
    }

    @Test public void foreachParamMapAliasesBindEachRepeatedRowExactlyOnce() throws Exception {
        for (ExecutorType type : ExecutorType.values()) {
            Fixture fixture = new Fixture(type);
            Row row = new Row();
            row.email = "a@example.com";
            List<Row> rows = Arrays.asList(row, row);
            MapperMethod.ParamMap<Object> parameters = new MapperMethod.ParamMap<>();
            parameters.put("list", rows);
            parameters.put("param1", rows);
            Configuration configuration = fixture.statement.getConfiguration();
            SqlSource source = new XMLLanguageDriver().createSqlSource(configuration,
                    "<script>insert into sample(email) values "
                            + "<foreach collection='list' item='row' separator=','>(#{row.email})</foreach>"
                            + "</script>", Map.class);
            MappedStatement statement = new MappedStatement.Builder(configuration,
                    fixture.statement.getId(), source, SqlCommandType.INSERT).build();
            fixture.executor.update(statement, parameters);
            assertEquals("a@example.com", row.email);
            fixture.executor.flushStatements();
            assertEquals(type.toString(), 2, fixture.bound.size());
            for (String value : fixture.bound) assertEquals("a@example.com", crypto.decryptWithCheckNoLog(value));
            assertSame(rows, parameters.get("list"));
            assertSame(rows, parameters.get("param1"));
            fixture.executor.close(true);
        }
    }

    @Test public void queryDisplayTokenRoundTripBindsOriginalCiphertext() throws Exception {
        reset(redis);
        Map<String, String> stored = new HashMap<>();
        when(redis.setexBatch(anyMap(), anyInt())).thenAnswer(call -> {
            stored.putAll((Map<String, String>) call.getArguments()[0]);
            return ResultUtil.SUCCESS_RESULT;
        });
        when(redis.get(anyString())).thenAnswer(call -> stored.get(call.getArguments()[0]));
        for (ExecutorType type : ExecutorType.values()) {
            Fixture fixture = new Fixture(type);
            Row row = new Row();
            row.email = crypto.encryptWithCheck("a@example.com");
            SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto,
                    new RedisRevealTokenStore(redis, 1800));
            processor.process(Collections.singletonList(row));
            String marker = row.email;
            assertTrue(marker, marker.startsWith("[#a***@example.com#VIEW:rt_"));
            fixture.executor.update(fixture.statement, row);
            fixture.executor.flushStatements();
            assertEquals(marker, row.email);
            assertEquals("a@example.com", crypto.decryptWithCheckNoLog(fixture.bound.get(0)));
            fixture.executor.close(true);
        }
    }

    private final class Fixture {
        final Connection connection = mock(Connection.class);
        final PreparedStatement prepared = mock(PreparedStatement.class);
        final List<String> bound = new ArrayList<>();
        final Executor executor;
        final MappedStatement statement;
        Fixture(ExecutorType type) throws Exception {
            Transaction transaction = mock(Transaction.class);
            when(transaction.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString(), anyInt())).thenReturn(prepared);
            when(connection.prepareStatement(anyString())).thenReturn(prepared);
            when(prepared.getConnection()).thenReturn(connection);
            when(prepared.getUpdateCount()).thenReturn(1);
            when(prepared.executeBatch()).thenReturn(new int[]{1, 1});
            doAnswer(call -> { bound.add((String) call.getArguments()[1]); return null; })
                    .when(prepared).setString(anyInt(), anyString());
            when(prepared.getGeneratedKeys()).thenAnswer(call -> {
                ResultSet keys = mock(ResultSet.class);
                ResultSetMetaData metadata = mock(ResultSetMetaData.class);
                when(keys.getMetaData()).thenReturn(metadata);
                when(metadata.getColumnCount()).thenReturn(1);
                when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
                if (type == ExecutorType.BATCH) when(keys.next()).thenReturn(true, true, false);
                else when(keys.next()).thenReturn(true, false);
                when(keys.getLong(1)).thenReturn(41L, 42L);
                return keys;
            });
            Configuration configuration = new Configuration();
            SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto,
                    new RedisRevealTokenStore(redis, 1800));
            configuration.addInterceptor(new AESInterceptor(processor, new AddressBookHandler(processor)));
            statement = new MappedStatement.Builder(configuration,
                    "com.union.control.mapper.SensitiveDataDemoMapper.insert",
                    parameter -> new BoundSql(configuration, "insert into sample(email) values (?)",
                            Collections.singletonList(new ParameterMapping.Builder(configuration,
                                    "email", String.class).build()), parameter), SqlCommandType.INSERT)
                    .keyGenerator(Jdbc3KeyGenerator.INSTANCE).keyProperty("id").build();
            executor = configuration.newExecutor(transaction, type);
        }
    }

    public static class Row {
        public Long id;
        @EnDecryptField public String email;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static final class AesService implements SymmetricalSecurityService {
        private Result<SecurityResult> crypt(int mode, byte[] input) {
            try {
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(mode, new SecretKeySpec(new byte[32], "AES"));
                return Result.success(new SecurityResult(cipher.doFinal(input)));
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            return crypt(Cipher.ENCRYPT_MODE, plaintext);
        }
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            return crypt(Cipher.DECRYPT_MODE, ciphertext);
        }
    }
}
