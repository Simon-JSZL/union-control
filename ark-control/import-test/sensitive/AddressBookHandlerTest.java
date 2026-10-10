package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AddressBookHandlerTest {
    private static final String PLAIN = "alice@example.com";
    private static final String MASK = "[#a***@example.com]";
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final AddressBookHandler handler = new AddressBookHandler(
            new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(redis, 1800)));

    @Before
    public void configureCrypto() throws Exception {
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn(PLAIN);
        when(crypto.encryptWithCheck(PLAIN)).thenReturn("cipher");
    }

    @Test
    public void missingProcessorIsRejected() {
        try {
            new AddressBookHandler(null);
            fail("Missing processor must be rejected");
        } catch (IllegalArgumentException error) {
            assertEquals("Reveal processor is required", error.getMessage());
        }
    }

    @Test
    public void selectAllowsScalarWhileWriteRequiresNamedSensitiveParameters() throws Exception {
        MappedStatement select = statement(SqlCommandType.SELECT, "select 1", false);
        handler.encryptForExecution(select, 7L).run();
        assertEquals("select 1", handler.prepare(select, 7L).getBoundSql(7L).getSql());
        MappedStatement update = statement(SqlCommandType.UPDATE, "update t set email = ?", false);
        try {
            handler.encryptForExecution(update, PLAIN);
            fail("Scalar sensitive writes must fail");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("named parameters"));
        }
        for (MappedStatement target : Arrays.asList(select, update)) {
            Map<String, Object> row = row("D3");
            row.put("email", PLAIN);
            Runnable restore = handler.encryptForExecution(target, row);
            assertEquals("cipher", row.get("email"));
            restore.run();
            assertEquals(PLAIN, row.get("email"));
        }
    }

    @Test
    public void prepareMigratesOnlyLegacyTableAndDisablesCache() {
        String sql = "select * from t_m_announce_address_book a join t_m_announce_address_book_new b "
                + "join other_t_m_announce_address_book c join t_m_announce_address_book_archive d";
        MappedStatement original = statement(SqlCommandType.SELECT, sql, true);
        MappedStatement prepared = handler.prepare(original, null);
        assertEquals(sql.replace("book a", "book_new a"), prepared.getBoundSql(null).getSql());
        assertFalse(prepared.isUseCache());
        assertTrue(original.isUseCache());
        MappedStatement unchanged = statement(SqlCommandType.SELECT, "select 1", false);
        assertSame(unchanged, handler.prepare(unchanged, null));
    }

    @Test
    public void prepareRendersDynamicSqlOnlyOnce() {
        Configuration config = new Configuration();
        SqlSource source = mock(SqlSource.class);
        when(source.getBoundSql(null)).thenReturn(new BoundSql(config,
                "select * from t_m_announce_address_book", Collections.emptyList(), null));
        MappedStatement original = new MappedStatement.Builder(config, "query", source,
                SqlCommandType.SELECT).build();
        MappedStatement prepared = handler.prepare(original, null);
        assertEquals("select * from t_m_announce_address_book_new", prepared.getBoundSql(null).getSql());
        verify(source).getBoundSql(null);
    }

    @Test
    public void strictModeOverridesRevealSwitchAndWhitelist() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            Map<String, Object> row = row("D3");
            handler.processResult(row, enabled, true, Collections.singleton("D3"));
            assertEquals(MASK, row.get("email"));
            assertEquals("D3", row.get("role"));
        }
    }

    @Test
    public void disabledRevealDecryptsWithoutRedisRegardlessOfRole() throws Exception {
        Map<String, Object> row = row("unknown");
        handler.processResult(row, false, false, Collections.emptySet());
        assertEquals(PLAIN, row.get("email"));
        verifyZeroInteractions(redis);
    }

    @Test
    public void dockingTypeTakesPrecedenceEvenWhenNullAndRoleIsOnlyFallback() throws Exception {
        for (String key : Arrays.asList("dockingType", "docking_type", "role")) {
            for (Object docking : Arrays.asList(" D3 ", "D4", null, 3)) {
                Map<String, Object> row = row("D3");
                row.put(key, docking);
                handler.processResult(row, true, false, Collections.singleton("D3"));
                assertEquals(" D3 ".equals(docking) ? PLAIN : MASK, row.get("email"));
            }
        }
        Map<String, Object> both = row("D3");
        both.put("dockingType", "D4");
        both.put("docking_type", "D3");
        handler.processResult(both, true, false, Collections.singleton("D3"));
        assertEquals(MASK, both.get("email"));
    }

    @Test
    public void beanPropertiesSupportDockingTypeRoleAndFailClosedOnMissingOrBrokenGetters() throws Exception {
        EmailRow docking = new DockingRow();
        EmailRow role = new RoleRow();
        EmailRow absent = new EmailRow();
        EmailRow broken = new BrokenRow();
        EmailRow nullDocking = new NullDockingRow();
        handler.processResult(new Object[]{docking, role, absent, broken, nullDocking},
                true, false, Collections.singleton("D3"));
        assertEquals(PLAIN, docking.email);
        assertEquals(PLAIN, role.email);
        assertEquals(MASK, absent.email);
        assertEquals(MASK, broken.email);
        assertEquals(MASK, nullDocking.email);
        verify(crypto).decryptWithCheckNoLog("cipher");
    }

    @Test
    public void nullRowPredicateFailsClosed() throws Exception {
        // The processor filters null rows before invoking this defensive predicate.
        Method predicate = AddressBookHandler.class.getDeclaredMethod("allowPlaintext", Object.class, Set.class);
        predicate.setAccessible(true);
        assertEquals(false, predicate.invoke(handler, null, Collections.singleton("D3")));
    }

    private static Map<String, Object> row(String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("email", "cipher");
        row.put("role", role);
        return row;
    }

    private static MappedStatement statement(SqlCommandType type, String sql, boolean cache) {
        Configuration config = new Configuration();
        return new MappedStatement.Builder(config, "addressBook",
                parameter -> new BoundSql(config, sql, Collections.emptyList(), parameter), type)
                .useCache(cache).build();
    }

    public static class EmailRow {
        @EnDecryptField String email = "cipher";
    }

    public static class RoleRow extends EmailRow {
        public String getRole() { return "D3"; }
    }

    public static class DockingRow extends RoleRow {
        public String getDockingType() { return " D3 "; }
    }

    public static class NullDockingRow extends RoleRow {
        public String getDockingType() { return null; }
    }

    public static class BrokenRow extends EmailRow {
        public String getRole() { throw new IllegalStateException("Unreadable role"); }
    }
}
