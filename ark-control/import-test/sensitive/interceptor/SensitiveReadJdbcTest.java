package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.junit.Test;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.io.StringReader;
import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real XML parsing, Executor and JDBC result mapping; only the JDBC connection is mocked. */
public class SensitiveReadJdbcTest {
    private static final String NAMESPACE = "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper";

    @Test public void copiedResultTypeAndResultMapsKeepAllColumnsAndExactlyOneQuery() throws Exception {
        for (String mapping : Arrays.asList("resultType", "resultMap", "map")) {
            for (boolean masking : new boolean[]{true, false}) {
              for (String dockingScenario : Arrays.asList("D3", "D4", "mixed")) {
                SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
                RedisCacheService redis = mock(RedisCacheService.class);
                when(crypto.decryptWithCheckNoLog(anyString())).thenAnswer(call ->
                        ((String) call.getArguments()[0]).substring("cipher:".length()));
                when(redis.setexBatch(anyMap(), anyInt())).thenReturn(ResultUtil.SUCCESS_RESULT);
                SensitiveRevealProcessor processor = new SensitiveRevealProcessor(crypto, new RedisRevealTokenStore(redis, 1800));
                AESInterceptor interceptor = new ReadInterceptor(processor, masking);
                Configuration config = new Configuration();
                config.setMapUnderscoreToCamelCase(true);
                config.addInterceptor(interceptor);
                String type = mapping.equals("map") ? "java.util.LinkedHashMap" : Row.class.getName();
                String result = mapping.equals("resultType") ? "resultType='" + type + "'" : "resultMap='row'";
                String xml = "<!DOCTYPE mapper PUBLIC '-//mybatis.org//DTD Mapper 3.0//EN' 'http://mybatis.org/dtd/mybatis-3-mapper.dtd'>"
                        + "<mapper namespace='" + NAMESPACE + "'><resultMap id='row' type='" + type + "'>"
                        + "<id property='id' column='id' javaType='java.lang.String'/>"
                        + "<result property='displayName' column='display_name' javaType='java.lang.String'/>"
                        + "<result property='email' column='email' javaType='java.lang.String'/>"
                        + "<result property='mobileNumber' column='mobile_number' javaType='java.lang.String'/>"
                        + "<result property='telNumber' column='tel_number' javaType='java.lang.String'/>"
                        + "<result property='dockingType' column='docking_type' javaType='java.lang.String'/>"
                        + "<result property='updatedAt' column='updated_at' javaType='java.sql.Timestamp'/>"
                        + "</resultMap><select id='getAddressBookPageResult' " + result + ">"
                        + "select * from t_m_announce_address_book order by id</select></mapper>";
                new XMLMapperBuilder(new StringReader(xml), config, mapping + ".xml", config.getSqlFragments()).parse();
                MappedStatement source = config.getMappedStatement(NAMESPACE + ".getAddressBookPageResult");
                assertEquals(1, source.getResultMaps().size());
                assertEquals(mapping.equals("map") ? LinkedHashMap.class : Row.class, source.getResultMaps().get(0).getType());
                MappedStatement copied = new AddressBookHandler(processor).prepare(source, null);
                assertEquals(source.getResultMaps(), copied.getResultMaps());
                assertSame(source.getResultMaps().get(0), copied.getResultMaps().get(0));

                Transaction transaction = mock(Transaction.class);
                Connection connection = mock(Connection.class);
                PreparedStatement statement = mock(PreparedStatement.class);
                when(transaction.getConnection()).thenReturn(connection);
                when(connection.prepareStatement(anyString())).thenReturn(statement);
                when(statement.execute()).thenReturn(true);
                CachedRowSet jdbcRows = rows(dockingScenario);
                List<String> expectedOrder = new ArrayList<>();
                while (jdbcRows.next()) expectedOrder.add(jdbcRows.getString("id"));
                jdbcRows.beforeFirst();
                when(statement.getResultSet()).thenReturn(jdbcRows);
                when(statement.getUpdateCount()).thenReturn(-1);
                Executor executor = config.newExecutor(transaction);
                List<Object> resultRows = executor.query(source, null, RowBounds.DEFAULT, null);
                assertEquals(100, resultRows.size());
                Set<String> ids = new HashSet<>();
                List<String> actualOrder = new ArrayList<>();
                for (Object value : resultRows) {
                    org.apache.ibatis.reflection.MetaObject row = config.newMetaObject(value);
                    String id = (String) row.getValue("id");
                    assertNotNull(id);
                    assertTrue("Duplicate result row: " + id, ids.add(id));
                    actualOrder.add(id);
                    assertEquals("name-" + id, row.getValue("displayName"));
                    String dockingType = dockingType(dockingScenario, Long.parseLong(id));
                    assertEquals(dockingType, row.getValue("dockingType"));
                    assertEquals(new Timestamp(1000L * Long.parseLong(id)), row.getValue("updatedAt"));
                    boolean protectedRow = masking && !"D3".equals(dockingType);
                    assertEquals(protectedRow, ((String) row.getValue("email")).contains("#VIEW:rt_"));
                    assertEquals(protectedRow, ((String) row.getValue("mobileNumber")).contains("#VIEW:rt_"));
                    assertEquals(protectedRow, ((String) row.getValue("telNumber")).contains("#VIEW:rt_"));
                    if (!protectedRow) assertEquals("alice@example.com", row.getValue("email"));
                }
                assertEquals(expectedOrder, actualOrder);
                verify(connection).prepareStatement("select * from t_m_announce_address_book_new order by id");
                verify(statement).execute();
                verify(crypto, times(3)).decryptWithCheckNoLog(anyString());
                verify(crypto, never()).encryptWithCheck(anyString());
                if (masking && !"D3".equals(dockingScenario)) verify(redis).setexBatch(anyMap(), eq(1800));
                else verifyZeroInteractions(redis);
                executor.close(true);
              }
            }
        }
    }

    private static String dockingType(String scenario, long id) {
        return "mixed".equals(scenario) ? (id % 2 == 0 ? "D3" : "D4") : scenario;
    }

    private static CachedRowSet rows(String dockingScenario) throws Exception {
        CachedRowSet result = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        String[] names = {"id", "display_name", "email", "mobile_number", "tel_number", "docking_type", "updated_at"};
        meta.setColumnCount(names.length);
        for (int i = 0; i < names.length; i++) {
            meta.setColumnName(i + 1, names[i]);
            meta.setColumnLabel(i + 1, names[i]);
            meta.setColumnType(i + 1, i == 0 ? Types.BIGINT : i == 6 ? Types.TIMESTAMP : Types.VARCHAR);
        }
        result.setMetaData(meta);
        for (long id = 1; id <= 100; id++) {
            result.moveToInsertRow();
            result.updateLong(1, id);
            result.updateString(2, "name-" + id);
            result.updateString(3, "cipher:alice@example.com");
            result.updateString(4, "cipher:13812345678");
            result.updateString(5, "cipher:021-12345678");
            result.updateString(6, dockingType(dockingScenario, id));
            result.updateTimestamp(7, new Timestamp(id * 1000));
            result.insertRow();
            result.moveToCurrentRow();
        }
        result.beforeFirst();
        return result;
    }

    public static class BaseRow { public String id; public Timestamp updatedAt; }
    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class ReadInterceptor extends AESInterceptor {
        private final boolean masking;
        ReadInterceptor(SensitiveRevealProcessor processor, boolean masking) {
            super(processor, new AddressBookHandler(processor));
            this.masking = masking;
        }
        @Override boolean checkIfStrictMode() { return false; }
        @Override boolean getRevealEnabled() { return masking; }
        @Override Set<String> getPlaintextRoles() { return Collections.singleton("D3"); }
    }
    public static class Row extends BaseRow {
        public String displayName;
        public String dockingType;
        @EnDecryptField public String email;
        @EnDecryptField public String mobileNumber;
        @EnDecryptField public String telNumber;
    }
}
