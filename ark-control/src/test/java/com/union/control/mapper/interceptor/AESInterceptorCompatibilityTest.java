package com.union.control.mapper.interceptor;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.nucc.channel.ark.common.util.Constant;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AESInterceptorCompatibilityTest {
    private static final String OLD_QUERY =
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.queryById";
    private static final String ENCRYPT_WRITE =
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookRecordMapper.insert";
    private static final String DECRYPT_QUERY =
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookRecordMapper.selectPageResult";
    private static final String DEMO_INSERT =
            "com.union.control.mapper.SensitiveDataDemoMapper.insert";
    private static final String DEMO_QUERY =
            "com.union.control.mapper.SensitiveDataDemoMapper.query";
    private static final String ADDRESS_BOOK_DEMO_INSERT =
            "com.union.control.mapper.SensitiveDataDemoMapper.insertAddressBook";
    private static final String ADDRESS_BOOK_DEMO_QUERY =
            "com.union.control.mapper.SensitiveDataDemoMapper.queryAddressBook";

    @After
    public void resetFlags() {
        Constant.flagMap.clear();
    }

    @Test
    public void unlistedStatementOnlyProceedsOnce() throws Throwable {
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement("example.Mapper.unlisted", SqlCommandType.SELECT,
                "select 1");
        when(executor.query(statement, null, RowBounds.DEFAULT, null))
                .thenReturn(Collections.emptyList());

        Object result = interceptor().intercept(queryInvocation(executor, statement, null));

        assertEquals(Collections.emptyList(), result);
        verify(executor, times(1)).query(statement, null, RowBounds.DEFAULT, null);
    }

    @Test
    public void oldQueryRewritesOnlyTheOldTableAndDecryptsAnnotatedFields() throws Throwable {
        Constant.flagMap.put("interceptorItems", "[]");
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement(OLD_QUERY, SqlCommandType.SELECT,
                "select * from t_m_announce_address_book where id = 1 "
                        + "union all select * from t_m_announce_address_book_new");
        Contact encrypted = new Contact(Base64.getEncoder().encodeToString(
                "enc:13800138000".getBytes(StandardCharsets.UTF_8)));
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) new ArrayList<>(
                Collections.singletonList(encrypted)));

        List<?> result = (List<?>) interceptor().intercept(queryInvocation(
                executor, statement, new HashMap<String, Object>()));

        assertEquals("13800138000", ((Contact) result.get(0)).mobile);
        org.mockito.ArgumentCaptor<MappedStatement> captured =
                org.mockito.ArgumentCaptor.forClass(MappedStatement.class);
        verify(executor).query(captured.capture(), any(), any(RowBounds.class),
                any(ResultHandler.class));
        String sql = captured.getValue().getBoundSql(Collections.emptyMap()).getSql();
        assertTrue(sql.contains("from t_m_announce_address_book_new where"));
        assertEquals(2, occurrences(sql, "t_m_announce_address_book_new"));
    }

    @Test
    public void encryptStatementUsesTheProductionAnnotationDispatch() throws Throwable {
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement(ENCRYPT_WRITE, SqlCommandType.INSERT,
                "insert into demo(value) values (?)");
        Contact contact = new Contact("13800138000");
        when(executor.update(any(MappedStatement.class), any())).thenAnswer(call -> {
            assertEquals(Base64.getEncoder().encodeToString(
                    "enc:13800138000".getBytes(StandardCharsets.UTF_8)),
                    ((Contact) call.getArguments()[1]).mobile);
            return 1;
        });

        assertEquals(1, interceptor().intercept(updateInvocation(executor, statement, contact)));
        assertEquals(Base64.getEncoder().encodeToString(
                "enc:13800138000".getBytes(StandardCharsets.UTF_8)), contact.mobile);
    }

    @Test
    public void demoStatementsEncryptWritesAndDecryptQueries() throws Throwable {
        Executor executor = mock(Executor.class);
        Contact parameter = new Contact("13800138000");
        MappedStatement insert = statement(DEMO_INSERT, SqlCommandType.INSERT,
                "insert into sensitive_data_demo.sensitive_data_demo(phone_number) values (?)");
        when(executor.update(any(MappedStatement.class), any())).thenReturn(1);

        interceptor().intercept(updateInvocation(executor, insert, parameter));

        String ciphertext = Base64.getEncoder().encodeToString(
                "enc:13800138000".getBytes(StandardCharsets.UTF_8));
        assertEquals(ciphertext, parameter.mobile);

        Contact row = new Contact(ciphertext);
        MappedStatement query = statement(DEMO_QUERY, SqlCommandType.SELECT,
                "select phone_number from sensitive_data_demo.sensitive_data_demo");
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) Collections.singletonList(row));

        interceptor().intercept(queryInvocation(executor, query, null));

        assertEquals("13800138000", row.mobile);
    }

    @Test
    public void addressBookDemoRouteStaysNarrowAndRewritesWrites() throws Throwable {
        Executor bypass = mock(Executor.class);
        Contact untouched = new Contact("13800138000");
        MappedStatement unexpected = statement(ADDRESS_BOOK_DEMO_QUERY + "Unexpected",
                SqlCommandType.INSERT, "insert into t_m_announce_address_book values (?)");
        when(bypass.update(unexpected, untouched)).thenReturn(1);

        interceptor().intercept(updateInvocation(bypass, unexpected, untouched));

        assertEquals("13800138000", untouched.mobile);
        verify(bypass).update(unexpected, untouched);

        Executor executor = mock(Executor.class);
        Contact parameter = new Contact("13800138000");
        MappedStatement insert = statement(ADDRESS_BOOK_DEMO_INSERT, SqlCommandType.INSERT,
                "insert into t_m_announce_address_book(mobile_number) values (?)");
        when(executor.update(any(MappedStatement.class), any())).thenReturn(1);

        interceptor().intercept(updateInvocation(executor, insert, parameter));

        assertEquals(Base64.getEncoder().encodeToString(
                "enc:13800138000".getBytes(StandardCharsets.UTF_8)), parameter.mobile);
        org.mockito.ArgumentCaptor<MappedStatement> captured =
                org.mockito.ArgumentCaptor.forClass(MappedStatement.class);
        verify(executor).update(captured.capture(), any());
        assertTrue(captured.getValue().getBoundSql(parameter).getSql()
                .contains("t_m_announce_address_book_new"));
        verify(executor, never()).clearLocalCache();
    }

    @Test
    public void demoSaveStatementsKeepEncryptionAndAddressBookBypass() throws Throwable {
        for (String method : new String[]{"insertSaved", "update", "updateAddressBook"}) {
            Executor executor = mock(Executor.class);
            Contact parameter = new Contact("13800138000");
            MappedStatement write = statement("com.union.control.mapper.SensitiveDataDemoMapper." + method,
                    SqlCommandType.UPDATE, "update t_m_announce_address_book set mobile_number = ?");
            when(executor.update(any(MappedStatement.class), any())).thenReturn(1);
            interceptor().intercept(updateInvocation(executor, write, parameter));
            assertEquals(Base64.getEncoder().encodeToString(
                    "enc:13800138000".getBytes(StandardCharsets.UTF_8)), parameter.mobile);
            org.mockito.ArgumentCaptor<MappedStatement> captured =
                    org.mockito.ArgumentCaptor.forClass(MappedStatement.class);
            verify(executor).update(captured.capture(), any());
            assertEquals(method.equals("updateAddressBook"),
                    captured.getValue().getBoundSql(parameter).getSql().contains("address_book_new"));
        }
    }

    @Test
    public void exactSavedRowQueriesStayMaskedInStrictModeIncludingWhitelistedRoles() throws Throwable {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto,
                new RedisRevealTokenStore(new RecordingStore(), 300));
        AESInterceptor interceptor = interceptor(processor, false, "001");
        org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "strictMode", true);
        for (String method : new String[]{"queryById", "queryAddressBookById"}) {
            Executor executor = mock(Executor.class);
            AddressBookContact row = new AddressBookContact("001", crypto.encryptWithCheck("13800138000"));
            when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class), any(ResultHandler.class)))
                    .thenReturn((List) Collections.singletonList(row));
            interceptor.intercept(queryInvocation(executor,
                    statement("com.union.control.mapper.SensitiveDataDemoMapper." + method,
                            SqlCommandType.SELECT, "select * from t_m_announce_address_book where id = ?"), null));
            assertTrue(row.mobile.startsWith("[#138****8000#VIEW:rt_"));
            org.mockito.ArgumentCaptor<MappedStatement> captured =
                    org.mockito.ArgumentCaptor.forClass(MappedStatement.class);
            verify(executor).query(captured.capture(), any(), any(RowBounds.class), any(ResultHandler.class));
            assertEquals(method.equals("queryAddressBookById"),
                    captured.getValue().getBoundSql(null).getSql().contains("address_book_new"));
        }
    }

    @Test
    public void pluginOnlyWrapsExecutors() {
        AESInterceptor interceptor = interceptor();
        Object other = new Object();
        Executor executor = mock(Executor.class);
        assertSame(other, interceptor.plugin(other));
        assertNotSame(executor, interceptor.plugin(executor));
    }

    @Test
    public void keepsTheProductionNoArgConstructionPath() throws Exception {
        assertTrue(AESInterceptor.class.newInstance() instanceof AESInterceptor);
    }

    @Test
    public void revealQueryDisablesMappedStatementCacheAndClearsExecutorCache() throws Throwable {
        PrefixGateway gateway = new PrefixGateway();
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(gateway);
        RecordingStore store = new RecordingStore();
        SensitiveRevealProcessor processor =
                new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(store, 300));
        AESInterceptor interceptor = interceptor(processor, true, "");
        Executor executor = mock(Executor.class);
        MappedStatement statement = cachedStatement(OLD_QUERY, "select * from demo");
        Contact row = new Contact(crypto.encryptWithCheck("13800138000"));
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) new ArrayList<>(
                Collections.singletonList(row)));

        interceptor.intercept(queryInvocation(executor, statement, new HashMap<String, Object>()));

        assertTrue(row.mobile.matches("\\[#138\\*{4}8000#VIEW:rt_[A-Za-z0-9_-]{43}\\]"));
        assertEquals(1, store.values.size());
        org.mockito.ArgumentCaptor<MappedStatement> captured =
                org.mockito.ArgumentCaptor.forClass(MappedStatement.class);
        verify(executor).query(captured.capture(), any(), any(RowBounds.class),
                any(ResultHandler.class));
        assertEquals(false, captured.getValue().isUseCache());
        verify(executor).clearLocalCache();
    }

    @Test
    public void revealAppliesToEveryGeneralDecryptQuery() throws Throwable {
        PrefixGateway gateway = new PrefixGateway();
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(gateway);
        RecordingStore store = new RecordingStore();
        SensitiveRevealProcessor processor =
                new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(store, 300));
        AESInterceptor interceptor = interceptor(processor, true, "");
        Executor executor = mock(Executor.class);
        MappedStatement statement = cachedStatement(DECRYPT_QUERY, "select * from demo");
        Contact row = new Contact(crypto.encryptWithCheck("13800138000"));
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) new ArrayList<>(
                Collections.singletonList(row)));

        interceptor.intercept(queryInvocation(executor, statement, null));

        assertTrue(row.mobile.matches("\\[#138\\*{4}8000#VIEW:rt_[A-Za-z0-9_-]{43}\\]"));
        assertEquals(1, store.values.size());
    }

    @Test
    public void disabledRevealReturnsPlaintextForGeneralDecryptQuery() throws Throwable {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement(DECRYPT_QUERY, SqlCommandType.SELECT,
                "select * from demo");
        Contact row = new Contact(crypto.encryptWithCheck("13800138000"));
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) new ArrayList<>(
                Collections.singletonList(row)));

        interceptor().intercept(queryInvocation(executor, statement, null));

        assertEquals("13800138000", row.mobile);
        verify(executor).clearLocalCache();
    }

    @Test
    public void addressBookRevealKeepsConfiguredRolesPlainAndMasksOtherRows() throws Throwable {
        PrefixGateway gateway = new PrefixGateway();
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(gateway);
        RecordingStore store = new RecordingStore();
        SensitiveRevealProcessor processor =
                new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(store, 300));
        AESInterceptor interceptor = interceptor(processor, true, "001,002");
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement(OLD_QUERY, SqlCommandType.SELECT,
                "select * from t_m_announce_address_book");
        AddressBookContact plaintext = new AddressBookContact("001",
                crypto.encryptWithCheck("13800138000"));
        AddressBookContact masked = new AddressBookContact("003",
                crypto.encryptWithCheck("13900139000"));
        List<AddressBookContact> rows = new ArrayList<>();
        rows.add(plaintext);
        rows.add(masked);
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) rows);

        interceptor.intercept(queryInvocation(executor, statement, null));

        assertEquals("13800138000", plaintext.mobile);
        assertTrue(masked.mobile.matches("\\[#139\\*{4}9000#VIEW:rt_[A-Za-z0-9_-]{43}\\]"));
        assertEquals(1, store.values.size());
    }

    @Test
    public void strictModeMasksEveryAddressBookRoleEvenWhenRevealIsDisabled() throws Throwable {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        RecordingStore store = new RecordingStore();
        SensitiveRevealProcessor processor =
                new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(store, 300));
        AESInterceptor interceptor = interceptor(processor, false, "001,002,003");
        org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "strictMode", true);
        List<AddressBookContact> rows = new ArrayList<>();
        for (String role : new String[]{"001", "002", "003", "unknown", "", null}) {
            rows.add(new AddressBookContact(role, crypto.encryptWithCheck("13800138000")));
        }
        Executor executor = mock(Executor.class);
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) rows);

        interceptor.intercept(queryInvocation(executor,
                statement(OLD_QUERY, SqlCommandType.SELECT,
                        "select * from t_m_announce_address_book"), null));

        for (AddressBookContact row : rows) {
            assertTrue(row.mobile.startsWith("[#138****8000#VIEW:rt_"));
        }
        assertEquals(rows.size(), store.values.size());
    }

    @Test
    public void strictModeMasksGeneralQueriesEvenWhenRevealIsDisabled() throws Throwable {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto,
                new RedisRevealTokenStore(new RecordingStore(), 300));
        AESInterceptor interceptor = interceptor(processor, false, "001");
        org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "strictMode", true);
        Contact row = new Contact(crypto.encryptWithCheck("13800138000"));
        Executor executor = mock(Executor.class);
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) Collections.singletonList(row));

        interceptor.intercept(queryInvocation(executor,
                statement(DECRYPT_QUERY, SqlCommandType.SELECT, "select * from demo"), null));

        assertTrue(row.mobile.startsWith("[#138****8000#VIEW:rt_"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidAddressBookRoleConfigurationFailsClosed() {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        new AddressBookHandler(new SensitiveRevealProcessor(crypto, null), "001,*");
    }

    @Test
    public void emptyDynamicItemsBypassConfiguredStatements() throws Throwable {
        Constant.flagMap.put("interceptorItems", "[]");
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement(ENCRYPT_WRITE, SqlCommandType.INSERT,
                "insert into demo(value) values (?)");
        Contact contact = new Contact("13800138000");
        when(executor.update(statement, contact)).thenReturn(1);

        assertEquals(1, interceptor().intercept(updateInvocation(executor, statement, contact)));

        assertEquals("13800138000", contact.mobile);
        verify(executor).update(statement, contact);
    }

    @Test
    public void nullAddressBookParameterStillRewritesAndDecrypts() throws Throwable {
        Executor executor = mock(Executor.class);
        MappedStatement statement = statement(OLD_QUERY, SqlCommandType.SELECT,
                "select * from t_m_announce_address_book");
        Contact encrypted = new Contact(Base64.getEncoder().encodeToString(
                "enc:13800138000".getBytes(StandardCharsets.UTF_8)));
        List<Contact> rows = new ArrayList<>(Collections.singletonList(encrypted));
        when(executor.query(any(MappedStatement.class), any(), any(RowBounds.class),
                any(ResultHandler.class))).thenReturn((List) rows);

        Object result = interceptor().intercept(queryInvocation(executor, statement, null));

        assertSame(rows, result);
        assertEquals("13800138000", encrypted.mobile);
        org.mockito.ArgumentCaptor<MappedStatement> captured =
                org.mockito.ArgumentCaptor.forClass(MappedStatement.class);
        verify(executor).query(captured.capture(), any(), any(RowBounds.class),
                any(ResultHandler.class));
        assertTrue(captured.getValue().getBoundSql(null).getSql()
                .contains("t_m_announce_address_book_new"));
    }

    private static AESInterceptor interceptor() {
        return interceptor(new SensitiveRevealProcessor(
                new SymmetricalSecurityUtils(new PrefixGateway()), null), false, "");
    }

    private static AESInterceptor interceptor(SensitiveRevealProcessor processor,
                                              boolean revealEnabled, String plaintextRoles) {
        return new AESInterceptor(processor,
                new AddressBookHandler(processor, plaintextRoles), revealEnabled);
    }

    private static MappedStatement statement(String id, SqlCommandType command, String sql) {
        Configuration configuration = new Configuration();
        return new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, sql), command).build();
    }

    private static MappedStatement cachedStatement(String id, String sql) {
        Configuration configuration = new Configuration();
        return new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, sql), SqlCommandType.SELECT)
                .useCache(true).build();
    }

    private static Invocation queryInvocation(Executor executor, MappedStatement statement,
                                              Object parameter) throws Exception {
        Method method = Executor.class.getMethod("query", MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class);
        return new Invocation(executor, method,
                new Object[]{statement, parameter, RowBounds.DEFAULT, null});
    }

    private static Invocation updateInvocation(Executor executor, MappedStatement statement,
                                               Object parameter) throws Exception {
        Method method = Executor.class.getMethod("update", MappedStatement.class, Object.class);
        return new Invocation(executor, method, new Object[]{statement, parameter});
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    static class Contact {
        @EnDecryptField
        String mobile;

        Contact(String mobile) {
            this.mobile = mobile;
        }
    }

    static class AddressBookContact extends Contact {
        String role;

        AddressBookContact(String role, String mobile) {
            super(mobile);
            this.role = role;
        }

        public String getRole() {
            return role;
        }
    }

    static class PrefixGateway implements SymmetricalSecurityService {
        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            return Result.success(new SecurityResult(
                    ("enc:" + new String(plaintext, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            return Result.success(new SecurityResult(
                    new String(ciphertext, StandardCharsets.UTF_8)
                            .substring(4).getBytes(StandardCharsets.UTF_8)));
        }
    }

    static class RecordingStore extends RedisCacheService {
        final List<String> values = new ArrayList<>();

        @Override
        public String setex(String key, int time, String value) {
            values.add(value);
            return ResultUtil.SUCCESS_RESULT;
        }
    }
}
