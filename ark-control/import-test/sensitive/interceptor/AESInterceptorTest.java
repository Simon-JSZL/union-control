package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.util.Constant;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AESInterceptorTest {
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(redis, 1800));
    private final AddressBookHandler addressBook = new AddressBookHandler(processor);
    private final Executor executor = mock(Executor.class);
    private final AESInterceptor interceptor = new AESInterceptor(processor, addressBook);
    private final Map savedFlags = new HashMap();
    private static final String PREFIX = "com.union.control.mapper.SensitiveDataDemoMapper.";

    @Before public void saveFlags() { savedFlags.putAll(Constant.flagMap); Constant.flagMap.clear(); }
    @After public void restoreFlags() { Constant.flagMap.clear(); Constant.flagMap.putAll(savedFlags); }

    private MappedStatement statement(String id, SqlCommandType type, boolean cache) {
        Configuration config = new Configuration();
        return new MappedStatement.Builder(config, id,
                p -> new BoundSql(config, "select 1", Collections.emptyList(), p), type).useCache(cache).build();
    }
    private Invocation query(MappedStatement statement, Object parameter) throws Exception {
        return new Invocation(executor, Executor.class.getMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class),
                new Object[]{statement, parameter, RowBounds.DEFAULT, null});
    }
    private Invocation update(MappedStatement statement, Object parameter) throws Exception {
        return new Invocation(executor, Executor.class.getMethod("update", MappedStatement.class, Object.class), new Object[]{statement, parameter});
    }
    private void enabled() { Constant.flagMap.put("interceptorItems", "[\"SensitiveDataDemoMapper\"]"); }

    @Test public void assertUnknownAndDisabledStatementsPassThrough() throws Throwable {
        for (String config : Arrays.asList("[]", "null", "[\"\",\"OtherMapper\"]")) {
            Constant.flagMap.put("interceptorItems", config);
            MappedStatement statement = statement(PREFIX + "query", SqlCommandType.SELECT, true);
            List<Object> result = Collections.singletonList(config);
            when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(result);
            assertSame(result, interceptor.intercept(query(statement, null)));
        }
        MappedStatement unknown = statement("unknown", SqlCommandType.UPDATE, false);
        when(executor.update(unknown, null)).thenReturn(7);
        assertEquals(7, interceptor.intercept(update(unknown, null)));
        verifyZeroInteractions(crypto, redis);
        verify(executor, never()).clearLocalCache();
    }

    @Test public void assertDefaultConfigurationAndUnrelatedFlagsHaveSameBehavior() throws Throwable {
        MappedStatement statement = statement(PREFIX + "insert", SqlCommandType.INSERT, false);
        when(executor.update(statement, null)).thenReturn(1);
        assertEquals(1, interceptor.intercept(update(statement, null)));
        Constant.flagMap.put("unrelated", "value");
        assertEquals(1, interceptor.intercept(update(statement, null)));
    }

    @Test public void assertEncryptsBeforeUpdating() throws Throwable {
        enabled();
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("email", "plain");
        when(crypto.encryptWithCheck("plain")).thenReturn("encrypted");
        MappedStatement statement = statement(PREFIX + "insert", SqlCommandType.INSERT, false);
        when(executor.update(statement, parameter)).thenReturn(3);
        assertEquals(3, interceptor.intercept(update(statement, parameter)));
        org.mockito.InOrder order = inOrder(crypto, executor);
        assertEquals("encrypted", parameter.get("email"));
        order.verify(crypto).encryptWithCheck("plain");
        order.verify(executor).update(statement, parameter);
    }

    @Test public void assertQueryDisablesCacheProcessesRowsAndAlwaysClearsLocalCache() throws Throwable {
        enabled();
        Map<String, Object> row = new HashMap<>();
        row.put("email", "cipher");
        List<Object> result = Collections.singletonList(row);
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("alice@example.com");
        when(crypto.encryptWithCheck("alice@example.com")).thenReturn("encrypted");
        when(executor.query(any(MappedStatement.class), anyObject(), eq(RowBounds.DEFAULT), isNull(ResultHandler.class))).thenReturn(result);
        for (boolean cache : new boolean[]{true, false}) {
            row.put("email", "cipher");
            MappedStatement original = statement(PREFIX + "query", SqlCommandType.SELECT, cache);
            Invocation invocation = query(original, new Object());
            assertSame(result, interceptor.intercept(invocation));
            assertFalse(((MappedStatement) invocation.getArgs()[0]).isUseCache());
            if (!cache) assertSame(original, invocation.getArgs()[0]);
        }
        assertEquals("[#a***@example.com]", row.get("email"));
        verify(crypto, times(2)).decryptWithCheckNoLog("cipher");
        verify(executor, times(2)).clearLocalCache();
    }

    @Test public void assertStrictModeStillProcessesWhenRevealDisabled() throws Throwable {
        enabled();
        AESInterceptor disabled = spy(interceptor);
        doReturn(false).when(disabled).getRevealEnabled();
        Map<String, Object> row = new HashMap<>();
        row.put("email", "cipher");
        List<Object> result = Collections.singletonList(row);
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("alice@example.com");
        when(crypto.encryptWithCheck("alice@example.com")).thenReturn("encrypted");
        MappedStatement statement = statement(PREFIX + "query", SqlCommandType.SELECT, false);
        when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(result);
        assertSame(result, disabled.intercept(query(statement, null)));
        assertEquals("[#a***@example.com]", row.get("email"));
        verify(crypto).decryptWithCheckNoLog("cipher");
    }

    @Test public void assertNullAndFailedQueriesClearCache() throws Throwable {
        enabled();
        MappedStatement statement = statement(PREFIX + "query", SqlCommandType.SELECT, false);
        when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(null).thenThrow(new IllegalStateException("query failed"));
        assertNull(interceptor.intercept(query(statement, null)));
        try { interceptor.intercept(query(statement, null)); fail("Expected failure"); }
        catch (InvocationTargetException e) { assertEquals("query failed", e.getCause().getMessage()); }
        verify(executor, times(2)).clearLocalCache();
        verifyZeroInteractions(crypto, redis);
    }

    @Test public void assertMissingDependenciesFailBeforeExecution() throws Throwable {
        enabled();
        AESInterceptor missing = new AESInterceptor();
        for (String id : Arrays.asList("insert", "insertAddressBook")) {
            try { missing.intercept(update(statement(PREFIX + id, SqlCommandType.INSERT, false), null)); fail("Expected missing dependency"); }
            catch (IllegalStateException e) { assertEquals("AESInterceptor sensitive dependencies are not configured", e.getMessage()); }
        }
        verifyZeroInteractions(executor);
    }

    @Test public void assertAddressBookWritesUsePreparedStatement() throws Throwable {
        MappedStatement original = statement(PREFIX + "insertAddressBook", SqlCommandType.INSERT, true);
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("email", "plain");
        when(crypto.encryptWithCheck("plain")).thenReturn("encrypted");
        when(executor.update(any(MappedStatement.class), eq(parameter))).thenReturn(2);
        Invocation invocation = update(original, parameter);
        assertEquals(2, interceptor.intercept(invocation));
        assertNotSame(original, invocation.getArgs()[0]);
        assertFalse(((MappedStatement) invocation.getArgs()[0]).isUseCache());
        assertEquals("encrypted", parameter.get("email"));
        verify(executor, never()).clearLocalCache();
    }

    @Test public void assertAddressBookQueriesHandleRowsNullAndFailure() throws Throwable {
        MappedStatement statement = statement(PREFIX + "queryAddressBook", SqlCommandType.SELECT, false);
        Map<String, Object> row = new HashMap<>();
        row.put("email", "cipher");
        List<Object> rows = Collections.singletonList(row);
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("alice@example.com");
        when(crypto.encryptWithCheck("alice@example.com")).thenReturn("encrypted");
        when(executor.query(statement, null, RowBounds.DEFAULT, null)).thenReturn(rows, null).thenThrow(new IllegalStateException("failed"));
        assertSame(rows, interceptor.intercept(query(statement, null)));
        assertNull(interceptor.intercept(query(statement, null)));
        try { interceptor.intercept(query(statement, null)); fail("Expected query failure"); }
        catch (InvocationTargetException e) { assertEquals("failed", e.getCause().getMessage()); }
        assertEquals("[#a***@example.com]", row.get("email"));
        verify(executor, times(3)).clearLocalCache();
        AESInterceptor disabled = spy(interceptor);
        doReturn(false).when(disabled).getRevealEnabled();
        row.put("email", "cipher");
        doReturn(rows).when(executor).query(statement, null, RowBounds.DEFAULT, null);
        assertSame(rows, disabled.intercept(query(statement, null)));
        assertEquals("[#a***@example.com]", row.get("email"));
        verify(crypto, times(2)).decryptWithCheckNoLog("cipher");
    }

    @Test public void assertCopyRetainsMetadataAndAdditionalParameters() {
        Configuration config = new Configuration();
        List<ParameterMapping> mappings = Arrays.asList(new ParameterMapping.Builder(config, "extra", String.class).build(), new ParameterMapping.Builder(config, "ordinary", String.class).build());
        Object parameter = new Object();
        BoundSql bound = new BoundSql(config, "select ?", mappings, parameter);
        bound.setAdditionalParameter("extra", "secret");
        MappedStatement source = new MappedStatement.Builder(config, "copy", p -> bound, SqlCommandType.SELECT)
                .resource("mapper.xml").fetchSize(10).timeout(20).keyProperty("id,other").keyColumn("id_col,other_col")
                .resultSets("first,second").databaseId("mysql").resultOrdered(true).flushCacheRequired(true).build();
        MappedStatement copied = AESInterceptor.copy(source, parameter, "select changed");
        assertEquals("select changed", copied.getBoundSql(parameter).getSql());
        assertEquals("secret", copied.getBoundSql(parameter).getAdditionalParameter("extra"));
        assertSame(parameter, copied.getBoundSql(parameter).getParameterObject());
        assertSame(mappings, copied.getBoundSql(parameter).getParameterMappings());
        assertEquals(source.getResource(), copied.getResource());
        assertEquals(source.getFetchSize(), copied.getFetchSize());
        assertEquals(source.getTimeout(), copied.getTimeout());
        assertArrayEquals(source.getKeyProperties(), copied.getKeyProperties());
        assertArrayEquals(source.getKeyColumns(), copied.getKeyColumns());
        assertArrayEquals(source.getResultSets(), copied.getResultSets());
        assertEquals(source.getDatabaseId(), copied.getDatabaseId());
        assertTrue(copied.isResultOrdered());
        assertTrue(copied.isFlushCacheRequired());
        assertFalse(copied.isUseCache());
        MappedStatement noCache = statement("noCache", SqlCommandType.SELECT, false);
        assertSame(noCache, AESInterceptor.copy(noCache, null, "select 1"));
        assertNotSame(noCache, AESInterceptor.copy(noCache, null, "select 2"));
    }

    @Test public void assertDefaultsAndRoleParsingValidateConfiguration() throws Exception {
        assertTrue(interceptor.getRevealEnabled());
        assertEquals(new LinkedHashSet<>(Arrays.asList("001", "002", "003")), interceptor.getPlaintextRoles());
        Method parse = AESInterceptor.class.getDeclaredMethod("parseRoles", String.class);
        parse.setAccessible(true);
        assertEquals(Collections.emptySet(), parse.invoke(null, new Object[]{null}));
        assertEquals(Collections.emptySet(), parse.invoke(null, "  "));
        assertEquals(new LinkedHashSet<>(Arrays.asList("001", "admin_2-X")), parse.invoke(null, "001, admin_2-X,001"));
        for (String invalid : Arrays.asList("001,", "bad role", "!", String.join("", Collections.nCopies(33, "a")))) {
            try { parse.invoke(null, invalid); fail("Expected invalid role"); }
            catch (InvocationTargetException e) { assertTrue(e.getCause() instanceof IllegalArgumentException); }
        }
    }

    @Test public void assertPluginWrapsOnlyExecutorsAndEmptyPropertiesAreAccepted() throws Exception {
        Object target = new Object();
        assertSame(target, interceptor.plugin(target));
        assertTrue(Proxy.isProxyClass(interceptor.plugin(executor).getClass()));
        interceptor.setProperties(new Properties());
        Method joined = AESInterceptor.class.getDeclaredMethod("joined", String[].class, Consumer.class);
        joined.setAccessible(true);
        Consumer<String> setter = mock(Consumer.class);
        joined.invoke(null, new String[0], setter);
        verifyZeroInteractions(setter);
        assertNotNull(interceptor);
    }
}
