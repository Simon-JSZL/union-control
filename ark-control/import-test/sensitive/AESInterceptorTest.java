package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.Constant;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AESInterceptorTest {
    private static final String MAIL = "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.";
    private static final String INSERT = MAIL + "insert";
    private static final String UPDATE = MAIL + "update";
    private static final String DEMO = "com.union.control.mapper.SensitiveDataDemoMapper.";
    private static final String READ = DEMO + "query";
    private static final String ADDRESS_BOOK =
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.";
    private static final String ADDRESS_READ = ADDRESS_BOOK + "queryById";
    private static final String PLAIN = "alice@example.com";
    private static final String MASK = "[#a***@example.com]";
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final Executor executor = mock(Executor.class);
    private final SensitiveRevealProcessor processor = new SensitiveRevealProcessor(
            crypto, new RedisRevealTokenStore(redis, 1800));
    private final AESInterceptor interceptor = spy(new AESInterceptor(processor, new AddressBookHandler(processor)));
    private final Map<String, String> savedFlags = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        savedFlags.putAll(Constant.flagMap);
        Constant.flagMap.clear();
        when(crypto.encryptWithCheck(PLAIN)).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn(PLAIN);
    }

    @After
    public void restoreGlobalFlags() {
        Constant.flagMap.clear();
        Constant.flagMap.putAll(savedFlags);
    }

    @Test
    public void unknownStatementsPassThroughWithoutSensitiveDependencies() throws Throwable {
        AESInterceptor unconfigured = new AESInterceptor();
        MappedStatement statement = statement("other.Mapper.update", SqlCommandType.UPDATE, false);
        Map<String, Object> row = row(PLAIN);
        when(executor.update(statement, row)).thenReturn(7);
        assertEquals(7, unconfigured.intercept(update(statement, row)));
        assertEquals(PLAIN, row.get("email"));
        verify(executor, never()).clearLocalCache();
        verifyZeroInteractions(crypto, redis);
    }

    @Test
    public void knownRoutesRejectMissingDependenciesBeforeSql() throws Throwable {
        AESInterceptor unconfigured = new AESInterceptor();
        for (String id : Arrays.asList(INSERT, READ, ADDRESS_READ)) {
            try {
                unconfigured.intercept(update(statement(id, SqlCommandType.UPDATE, false), null));
                fail("Missing dependency accepted on " + id);
            } catch (IllegalStateException error) {
                assertEquals("AESInterceptor sensitive dependencies are not configured", error.getMessage());
            }
        }
        verifyZeroInteractions(executor);
    }

    @Test
    public void writesAlwaysEncryptRestoreInputsAndKeepGeneratedKeys() throws Throwable {
        doReturn(false).when(interceptor).checkIfStrictMode();
        doReturn(false).when(interceptor).getRevealEnabled();
        MappedStatement statement = statement(INSERT, SqlCommandType.INSERT, false);
        Map<String, Object> row = row(PLAIN);
        when(executor.update(statement, row)).thenAnswer(call -> {
            assertEquals("cipher", row.get("email"));
            row.put("id", 19L);
            return 1;
        });
        for (String config : Arrays.asList("[]", "null", "invalid-json", "[\"Other\"]")) {
            Constant.flagMap.put("interceptorItems", config);
            assertEquals(1, interceptor.intercept(update(statement, row)));
            assertEquals(PLAIN, row.get("email"));
            assertEquals(19L, row.get("id"));
        }
        verify(crypto, times(4)).encryptWithCheck(PLAIN);
        verifyZeroInteractions(redis);
    }

    @Test
    public void failedWritesRestoreCallerInputsForRetry() throws Throwable {
        for (String id : Arrays.asList(UPDATE, MAIL + "batchUpdateMailAudit")) {
            MappedStatement statement = statement(id, SqlCommandType.UPDATE, false);
            Map<String, Object> row = row(PLAIN);
            IllegalStateException failure = new IllegalStateException("SQL failed");
            when(executor.update(statement, row)).thenAnswer(call -> {
                assertEquals("cipher", row.get("email"));
                throw failure;
            });
            try {
                interceptor.intercept(update(statement, row));
                fail("SQL failure must propagate");
            } catch (InvocationTargetException error) {
                assertSame(failure, error.getCause());
            }
            assertEquals(PLAIN, row.get("email"));
        }
        verify(executor, never()).clearLocalCache();
    }

    @Test
    public void unresolvedMasksAndInvalidCiphertextNeverReachSql() throws Throwable {
        for (String id : Arrays.asList(UPDATE, MAIL + "batchUpdateMailAudit")) {
            Map<String, Object> row = row("[#a***@example.com]");
            try {
                interceptor.intercept(update(statement(id, SqlCommandType.UPDATE, false), row));
                fail("Unresolved mask reached SQL");
            } catch (IllegalArgumentException error) {
                assertEquals("[#a***@example.com]", row.get("email"));
            }
        }
        when(crypto.encryptWithCheck(PLAIN)).thenReturn(PLAIN);
        try {
            interceptor.intercept(update(statement(INSERT, SqlCommandType.INSERT, false), row(PLAIN)));
            fail("Unencrypted value reached SQL");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("invalid result"));
        }
        verifyZeroInteractions(executor);
    }

    @Test
    public void readSwitchTruthTableMasksOnlyWhenRequiredAndAlwaysClearsCache() throws Throwable {
        for (boolean strict : new boolean[]{false, true}) {
            for (boolean enabled : new boolean[]{false, true}) {
                for (boolean configured : new boolean[]{false, true}) {
                    doReturn(strict).when(interceptor).checkIfStrictMode();
                    doReturn(enabled).when(interceptor).getRevealEnabled();
                    Constant.flagMap.put("interceptorItems", configured
                            ? "[\"SensitiveDataDemoMapper\"]" : "[]");
                    Map<String, Object> row = row("cipher");
                    List<Object> rows = Collections.singletonList(row);
                    MappedStatement statement = statement(READ, SqlCommandType.SELECT, false);
                    when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(rows);
                    assertSame(rows, interceptor.intercept(query(statement, null)));
                    assertEquals(strict || enabled && configured ? MASK : PLAIN, row.get("email"));
                }
            }
        }
        verify(executor, times(8)).clearLocalCache();
    }

    @Test
    public void interceptorConfigurationHandlesAbsentNullEmptyAndMatchingItems() throws Throwable {
        doReturn(false).when(interceptor).checkIfStrictMode();
        for (String config : Arrays.asList(null, "null", "[]", "[\"\",\"other\"]",
                "[\"other\",\"SensitiveDataDemoMapper\"]")) {
            Constant.flagMap.clear();
            if (config != null) Constant.flagMap.put("interceptorItems", config);
            Map<String, Object> row = row("cipher");
            MappedStatement statement = statement(READ, SqlCommandType.SELECT, false);
            when(executor.query(statement, null, RowBounds.DEFAULT, null))
                    .thenReturn(Collections.singletonList(row));
            interceptor.intercept(query(statement, null));
            boolean defaultMatch = Arrays.stream(Constant.INTERCEPTOR_ITEMS.split(","))
                    .anyMatch(item -> !item.isEmpty() && statement.getId().contains(item));
            boolean masks = config == null ? defaultMatch : config.contains("SensitiveDataDemoMapper");
            assertEquals(masks ? MASK : PLAIN, row.get("email"));
        }
        Constant.flagMap.clear();
        Constant.flagMap.put("unrelated", "true");
        Map<String, Object> row = row("cipher");
        MappedStatement statement = statement(READ, SqlCommandType.SELECT, false);
        when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(Collections.singletonList(row));
        interceptor.intercept(query(statement, null));
        boolean defaultMatch = Arrays.stream(Constant.INTERCEPTOR_ITEMS.split(","))
                .anyMatch(item -> !item.isEmpty() && statement.getId().contains(item));
        assertEquals(defaultMatch ? MASK : PLAIN, row.get("email"));
    }

    @Test
    public void cachedReadUsesCacheDisabledCopyAndLeavesOriginalUntouched() throws Throwable {
        Map<String, Object> row = row("cipher");
        List<Object> rows = Collections.singletonList(row);
        when(executor.query(any(MappedStatement.class), isNull(), eq(RowBounds.DEFAULT),
                isNull(ResultHandler.class))).thenReturn(rows);
        for (boolean cache : new boolean[]{true, false}) {
            row.put("email", "cipher");
            MappedStatement original = statement(READ, SqlCommandType.SELECT, cache);
            Invocation invocation = query(original, null);
            assertSame(rows, interceptor.intercept(invocation));
            assertFalse(((MappedStatement) invocation.getArgs()[0]).isUseCache());
            assertEquals(cache, original.isUseCache());
            assertEquals(MASK, row.get("email"));
            if (!cache) assertSame(original, invocation.getArgs()[0]);
        }
    }

    @Test
    public void nullResultAndSqlFailureClearReadCacheOnBothRoutes() throws Throwable {
        for (String id : Arrays.asList(READ, ADDRESS_READ)) {
            MappedStatement statement = statement(id, SqlCommandType.SELECT, false);
            RuntimeException failure = new IllegalStateException("Query failed");
            when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(null).thenThrow(failure);
            assertNull(interceptor.intercept(query(statement, null)));
            try {
                interceptor.intercept(query(statement, null));
                fail("Query failure must propagate");
            } catch (InvocationTargetException error) {
                assertSame(failure, error.getCause());
            }
        }
        verify(executor, times(4)).clearLocalCache();
    }

    @Test
    public void addressBookVisibilityTruthTableUsesRolesOnlyOutsideStrictMode() throws Throwable {
        for (boolean strict : new boolean[]{false, true}) {
            for (boolean enabled : new boolean[]{false, true}) {
                doReturn(strict).when(interceptor).checkIfStrictMode();
                doReturn(enabled).when(interceptor).getRevealEnabled();
                Map<String, Object> allowed = row("cipher");
                allowed.put("dockingType", "001");
                Map<String, Object> denied = row("cipher");
                denied.put("docking_type", "other");
                List<Object> rows = Arrays.asList(allowed, denied);
                MappedStatement statement = statement(DEMO + "queryAddressBookById", SqlCommandType.SELECT, false);
                when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(rows);
                assertSame(rows, interceptor.intercept(query(statement, null)));
                assertEquals(strict ? MASK : PLAIN, allowed.get("email"));
                assertEquals(strict || enabled ? MASK : PLAIN, denied.get("email"));
            }
        }
        verify(executor, times(4)).clearLocalCache();
    }

    @Test
    public void addressBookPreparesSqlWithEncryptedParametersAndRestoresAfterQuery() throws Throwable {
        Configuration config = new Configuration();
        Map<String, Object> parameters = row(PLAIN);
        MappedStatement original = new MappedStatement.Builder(config, ADDRESS_READ,
                p -> new BoundSql(config, "select * from t_m_announce_address_book where email = ?",
                        Collections.emptyList(), p), SqlCommandType.SELECT).build();
        when(executor.query(any(MappedStatement.class), eq(parameters), eq(RowBounds.DEFAULT),
                isNull(ResultHandler.class))).thenAnswer(call -> {
            assertEquals("cipher", parameters.get("email"));
            MappedStatement prepared = (MappedStatement) call.getArguments()[0];
            assertEquals("select * from t_m_announce_address_book_new where email = ?",
                    prepared.getBoundSql(parameters).getSql());
            assertFalse(prepared.isUseCache());
            return Collections.emptyList();
        });
        assertEquals(Collections.emptyList(), interceptor.intercept(query(original, parameters)));
        assertEquals(PLAIN, parameters.get("email"));
        verify(executor).clearLocalCache();
    }

    @Test
    public void productionAddressBookWritesRemainOutsideQueryMigration() throws Throwable {
        // Production AddressBook writes have their own manual encryption boundary.
        for (String id : Arrays.asList(ADDRESS_BOOK + "insert", ADDRESS_BOOK + "updateByPrimaryKeySelective")) {
            MappedStatement original = statement(id, SqlCommandType.UPDATE, true);
            Map<String, Object> row = row("already-encrypted-by-host");
            when(executor.update(original, row)).thenAnswer(call -> {
                assertEquals("already-encrypted-by-host", row.get("email"));
                return 2;
            });
            Invocation invocation = update(original, row);
            assertEquals(2, interceptor.intercept(invocation));
            assertSame(original, invocation.getArgs()[0]);
            assertEquals("already-encrypted-by-host", row.get("email"));
        }
        verify(executor, never()).clearLocalCache();
        verifyZeroInteractions(crypto, redis);
    }

    @Test
    public void configuredPlaintextRolesReplaceDefaultsAndEmptyWhitelistMasksEveryone() throws Throwable {
        // These stubs represent values resolved by commonddcache; cache access is a host integration point.
        doReturn(false).when(interceptor).checkIfStrictMode();
        doReturn(true).when(interceptor).getRevealEnabled();
        for (Set<String> roles : Arrays.asList(Collections.singleton("D3"), Collections.<String>emptySet())) {
            doReturn(roles).when(interceptor).getPlaintextRoles();
            Map<String, Object> configured = row("cipher");
            configured.put("dockingType", "D3");
            Map<String, Object> defaultRole = row("cipher");
            defaultRole.put("dockingType", "001");
            List<Object> rows = Arrays.asList(configured, defaultRole);
            MappedStatement statement = statement(DEMO + "queryAddressBookById", SqlCommandType.SELECT, false);
            when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(rows);
            assertSame(rows, interceptor.intercept(query(statement, null)));
            assertEquals(roles.contains("D3") ? PLAIN : MASK, configured.get("email"));
            assertEquals(MASK, defaultRole.get("email"));
        }
    }

    @Test
    public void productionReadsAlwaysReturnPlaintextWithoutRedis() throws Throwable {
        for (String field : Arrays.asList("ADDRESS_BOOK", "DECRYPT")) {
            java.lang.reflect.Field routes = AESInterceptor.class.getDeclaredField(field);
            routes.setAccessible(true);
            for (String id : (Set<String>) routes.get(null)) {
                if (id.startsWith(DEMO)) continue;
                for (boolean strict : new boolean[]{false, true}) {
                    doReturn(strict).when(interceptor).checkIfStrictMode();
                    doReturn(true).when(interceptor).getRevealEnabled();
                    Map<String, Object> row = row("cipher");
                    MappedStatement statement = statement(id, SqlCommandType.SELECT, false);
                    when(executor.query(statement, null, RowBounds.DEFAULT, null))
                            .thenReturn(Collections.singletonList(row));
                    interceptor.intercept(query(statement, null));
                    assertEquals(id, PLAIN, row.get("email"));
                }
            }
        }
        verifyZeroInteractions(redis);
    }

    @Test
    public void addressBookEntityDecryptsOutsideRevealListButUnknownMapperBypasses() throws Throwable {
        for (String id : Arrays.asList(ADDRESS_READ, "other.AddressBookMapper.list")) {
            com.union.control.mapper.SensitiveAddressBookDemo row =
                    new com.union.control.mapper.SensitiveAddressBookDemo();
            row.setEmail("cipher");
            row.setTelephone("cipher");
            row.setMobileNumber("cipher");
            MappedStatement statement = statement(id, SqlCommandType.SELECT, false);
            when(executor.query(statement, null, RowBounds.DEFAULT, null))
                    .thenReturn(Collections.singletonList(row));
            interceptor.intercept(query(statement, null));
            String expected = ADDRESS_READ.equals(id) ? PLAIN : "cipher";
            assertEquals(expected, row.getEmail());
            assertEquals(expected, row.getTelephone());
            assertEquals(expected, row.getMobileNumber());
        }
        verifyZeroInteractions(redis);
    }

    @Test
    public void copyPreservesMetadataMappingsAndForeachAdditionalParameters() {
        Configuration config = new Configuration();
        Object parameter = new Object();
        List<ParameterMapping> mappings = Arrays.asList(
                new ParameterMapping.Builder(config, "__frch_row_0.email", String.class).build(),
                new ParameterMapping.Builder(config, "ordinary", String.class).build());
        BoundSql bound = new BoundSql(config, "select ?", mappings, parameter);
        bound.setAdditionalParameter("__frch_row_0.email", "cipher");
        MappedStatement original = new MappedStatement.Builder(config, "copy", p -> bound, SqlCommandType.SELECT)
                .resource("mapper.xml").fetchSize(10).timeout(20).keyProperty("id,sequence")
                .keyColumn("id_col,sequence_col").resultSets("one,two").databaseId("mysql")
                .resultOrdered(true).flushCacheRequired(true).build();
        MappedStatement copy = AESInterceptor.copy(original, parameter, "select changed");
        assertEquals("select changed", copy.getBoundSql(null).getSql());
        assertEquals("cipher", copy.getBoundSql(null).getAdditionalParameter("__frch_row_0.email"));
        assertFalse(copy.getBoundSql(null).hasAdditionalParameter("ordinary"));
        assertSame(parameter, copy.getBoundSql(null).getParameterObject());
        assertSame(mappings, copy.getBoundSql(null).getParameterMappings());
        assertSame(original.getConfiguration(), copy.getConfiguration());
        assertEquals(original.getResultMaps(), copy.getResultMaps());
        assertSame(original.getParameterMap(), copy.getParameterMap());
        assertSame(original.getKeyGenerator(), copy.getKeyGenerator());
        assertSame(original.getLang(), copy.getLang());
        assertSame(original.getCache(), copy.getCache());
        assertEquals(original.getStatementType(), copy.getStatementType());
        assertEquals(original.getResultSetType(), copy.getResultSetType());
        assertEquals(original.getSqlCommandType(), copy.getSqlCommandType());
        assertEquals(original.getId(), copy.getId());
        assertEquals(original.getResource(), copy.getResource());
        assertEquals(original.getFetchSize(), copy.getFetchSize());
        assertEquals(original.getTimeout(), copy.getTimeout());
        assertEquals(original.getDatabaseId(), copy.getDatabaseId());
        assertArrayEquals(original.getKeyProperties(), copy.getKeyProperties());
        assertArrayEquals(original.getKeyColumns(), copy.getKeyColumns());
        assertArrayEquals(original.getResultSets(), copy.getResultSets());
        assertTrue(copy.isResultOrdered());
        assertTrue(copy.isFlushCacheRequired());
        assertFalse(copy.isUseCache());
        MappedStatement noCache = statement("copy", SqlCommandType.SELECT, false);
        assertSame(noCache, AESInterceptor.copy(noCache, null, "select 1"));
        assertNotSame(noCache, AESInterceptor.copy(noCache, null, "select 2"));
    }

    @Test
    public void defaultsAndRoleParsingValidateWhitespaceDuplicatesAndBounds() throws Exception {
        assertTrue(interceptor.getRevealEnabled());
        assertTrue(interceptor.checkIfStrictMode());
        assertEquals(new LinkedHashSet<>(Arrays.asList("001", "002", "003")), interceptor.getPlaintextRoles());
        Method parse = AESInterceptor.class.getDeclaredMethod("parseRoles", String.class);
        parse.setAccessible(true);
        assertEquals(Collections.emptySet(), parse.invoke(null, new Object[]{null}));
        assertEquals(Collections.emptySet(), parse.invoke(null, "  "));
        assertEquals(new LinkedHashSet<>(Arrays.asList("D3", "admin_2-X")),
                parse.invoke(null, "D3, admin_2-X,D3"));
        for (String invalid : Arrays.asList("D3,", "bad role", "!",
                String.join("", Collections.nCopies(33, "a")))) {
            try {
                parse.invoke(null, invalid);
                fail("Invalid role accepted");
            } catch (InvocationTargetException error) {
                assertTrue(error.getCause() instanceof IllegalArgumentException);
            }
        }
    }

    @Test
    public void pluginWrapsExecutorsOnlyAndMetadataJoinIgnoresMissingArrays() throws Exception {
        Object ordinary = new Object();
        assertSame(ordinary, interceptor.plugin(ordinary));
        assertNull(interceptor.plugin(null));
        assertTrue(Proxy.isProxyClass(new AESInterceptor(processor, new AddressBookHandler(processor))
                .plugin(executor).getClass()));
        interceptor.setProperties(new Properties());
        Consumer<String> setter = mock(Consumer.class);
        Method join = AESInterceptor.class.getDeclaredMethod("joined", String[].class, Consumer.class);
        join.setAccessible(true);
        join.invoke(null, null, setter);
        join.invoke(null, new String[0], setter);
        verifyZeroInteractions(setter);
    }

    private static Map<String, Object> row(String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("email", email);
        return row;
    }

    private static MappedStatement statement(String id, SqlCommandType type, boolean cache) {
        Configuration config = new Configuration();
        return new MappedStatement.Builder(config, id,
                p -> new BoundSql(config, "select 1", Collections.emptyList(), p), type).useCache(cache).build();
    }

    private Invocation query(MappedStatement statement, Object parameter) throws Exception {
        return new Invocation(executor, Executor.class.getMethod("query", MappedStatement.class,
                Object.class, RowBounds.class, ResultHandler.class),
                new Object[]{statement, parameter, RowBounds.DEFAULT, null});
    }

    private Invocation update(MappedStatement statement, Object parameter) throws Exception {
        return new Invocation(executor, Executor.class.getMethod("update", MappedStatement.class, Object.class),
                new Object[]{statement, parameter});
    }
}
