package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AddressBookHandlerTest {
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(redis, 1800));
    private final AddressBookHandler handler = new AddressBookHandler(processor);

    @Before public void configureCrypto() throws Exception {
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("alice@example.com");
        when(crypto.encryptWithCheck("alice@example.com")).thenReturn("cipher");
    }

    private Map<String, Object> row(String role) {
        Map<String, Object> row = new HashMap<>();
        row.put("role", role);
        row.put("email", "cipher");
        return row;
    }

    @Test public void assertConstructorRejectsMissingProcessor() {
        try { new AddressBookHandler(null); fail("Missing processor must fail"); }
        catch (IllegalArgumentException e) { assertEquals("Reveal processor is required", e.getMessage()); }
    }

    @Test public void assertPrepareEncryptsAndMigratesOnlyExactLegacyTable() throws Exception {
        Configuration config = new Configuration();
        Map<String, Object> parameter = row("001");
        parameter.put("email", "alice@example.com");
        String sql = "select * from t_m_announce_address_book join t_m_announce_address_book_new n join other_t_m_announce_address_book x";
        BoundSql bound = new BoundSql(config, sql, Collections.emptyList(), parameter);
        MappedStatement original = new MappedStatement.Builder(config, "query", p -> bound, SqlCommandType.SELECT).build();
        MappedStatement prepared = handler.prepare(original, parameter);
        assertEquals(sql.replace("from t_m_announce_address_book ", "from t_m_announce_address_book_new "), prepared.getBoundSql(parameter).getSql());
        assertFalse(prepared.isUseCache());
        assertEquals("cipher", parameter.get("email"));
        verify(crypto).encryptWithCheck("alice@example.com");
    }

    @Test public void assertStrictModeProtectsEntireResultEvenWhenRevealDisabled() throws Exception {
        Map<String, Object> result = row("001");
        handler.processResult(result, false, true, Collections.singleton("001"));
        assertEquals("[#a***@example.com]", result.get("email"));
        verify(crypto).decryptWithCheckNoLog("cipher");
    }

    @Test public void assertDisabledRevealDecryptsEntireResult() throws Exception {
        Map<String, Object> result = row("004");
        handler.processResult(result, false, false, Collections.emptySet());
        assertEquals("alice@example.com", result.get("email"));
        verify(crypto).decryptWithCheckNoLog("cipher");
        verifyZeroInteractions(redis);
    }

    @Test public void assertIterableSeparatesAllowedRolesAndProtectsMalformedRows() throws Exception {
        Map<String, Object> allowed = row(" 001 ");
        Map<String, Object> denied = row("004");
        Map<String, Object> noRole = row(null);
        RoleBean bean = new RoleBean();
        BrokenRoleBean broken = new BrokenRoleBean();
        NoRoleBean noGetter = new NoRoleBean();
        List<Object> rows = Arrays.asList(allowed, denied, noRole, bean, broken, noGetter, null);
        handler.processResult(rows, true, false, Collections.singleton("001"));
        assertEquals("alice@example.com", allowed.get("email"));
        assertEquals("alice@example.com", bean.email);
        assertEquals("[#a***@example.com]", denied.get("email"));
        assertEquals("[#a***@example.com]", noRole.get("email"));
        assertEquals("[#a***@example.com]", broken.email);
        assertEquals("[#a***@example.com]", noGetter.email);
    }

    @Test public void assertArraysAndSingleRowsAndNullAreHandled() throws Exception {
        Map<String, Object> row = row("001");
        for (Object result : Arrays.asList(new Object[]{row}, row, null, new int[]{7})) {
            row.put("email", "cipher");
            handler.processResult(result, true, false, Collections.singleton("001"));
            assertEquals(result == null || result instanceof int[] ? "cipher" : "alice@example.com", row.get("email"));
            if (result instanceof int[]) assertArrayEquals(new int[]{7}, (int[]) result);
        }
    }

    public static class NoRoleBean { @EnDecryptField String email = "cipher"; }
    public static class RoleBean extends NoRoleBean { public String getRole() { return "001"; } }
    public static class BrokenRoleBean extends NoRoleBean { public String getRole() { throw new IllegalStateException("bad row"); } }
}
