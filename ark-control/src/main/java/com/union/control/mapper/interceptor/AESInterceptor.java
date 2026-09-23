package com.union.control.mapper.interceptor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.nucc.channel.ark.common.util.Constant;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update", args = {
                MappedStatement.class, Object.class})
})
public class AESInterceptor implements Interceptor {
    private static final Set<String> ADDRESS_BOOK = statements(
            "com.union.control.mapper.SensitiveDataDemoMapper.insertAddressBook",
            "com.union.control.mapper.SensitiveDataDemoMapper.updateAddressBook",
            "com.union.control.mapper.SensitiveDataDemoMapper.queryAddressBookById",
            "com.union.control.mapper.SensitiveDataDemoMapper.queryAddressBook",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.getAddressBookPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.queryById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.selectAllUsers",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.selectByOrgCodeList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.selectByOrgCodeAndParaList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.getUserEmail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceUserGroupRelationMapper.getAddressMailOfBusinessType",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceUserGroupRelationMapper.selectNewUserByGroupId",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.queryByUserAccount",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceUserGroupRelationMapper.selectUserByGroupId");
    private static final Set<String> DECRYPT = statements(
            "com.union.control.mapper.SensitiveDataDemoMapper.query",
            "com.union.control.mapper.SensitiveDataDemoMapper.queryById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookRecordMapper.selectPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.queryReformTrackItemsById",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.queryReformTrackItemsByConditions",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.queryQuestionnaireListByUpdateOrgCodeAndNo",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.queryQuestionnaireListByParam",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.selectAnnounceMailPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryAnnounceMailSend",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.getByBatchId",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.getBy",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.getById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryBySeriesNo",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryByIds",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryBySeriesNoS",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.selectJiraNoticeResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.selectMailList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.selectAnnounceMailPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.queryAnnounceMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.listBy",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.getNormalMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.checkMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.getAnnounceMailById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.getAutoAuditPass",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.queryBySeriesNos",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.queryNoticeExemptionMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.selectAppealMailPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.selectRelatedInfo",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.selectAppealPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.selectByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.selectProcess",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.getTicketById",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.queryTicketListByParamsPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.exportTicketListByParams",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.queryTicketByOrgCode",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.selectByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.selectByRecord",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.selectPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.queryLastNotify",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.queryLastDashBoardNotify");
    private static final Set<String> ENCRYPT = statements(
            "com.union.control.mapper.SensitiveDataDemoMapper.insert",
            "com.union.control.mapper.SensitiveDataDemoMapper.insertSaved",
            "com.union.control.mapper.SensitiveDataDemoMapper.update",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookRecordMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.batchInsertReformTrackItems",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.batchUpdateReformTrackItems",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.updateQuestionnaire",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.insertBatchQuestionnaireList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.insertBatch",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.insertBatch",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.batchUpdateMailAudit",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.update",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.insertSelective",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.updateByPrimaryKeySelective",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.updateByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.insertSelective",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.updateByPrimaryKeySelective",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.updateByPrimaryKeyWithBLOBs",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.updateByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.insert");

    @Autowired
    private SensitiveRevealProcessor processor;
    @Autowired
    private AddressBookHandler addressBook;
    private static final Logger log = LoggerFactory.getLogger(AESInterceptor.class);

    public AESInterceptor() {}

    AESInterceptor(SensitiveRevealProcessor processor, AddressBookHandler addressBook) {
        this.processor = processor;
        this.addressBook = addressBook;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        if (ADDRESS_BOOK.contains(statement.getId())) {
            return interceptAddressBook(invocation, statement);
        }

        boolean encrypt = ENCRYPT.contains(statement.getId());
        if (!encrypt && !DECRYPT.contains(statement.getId())) return invocation.proceed();
        if (encrypt) {
            requireProcessor();
            Runnable restore = processor.encryptForExecution(invocation.getArgs()[1]);
            try {
                return invocation.proceed();
            } finally {
                restore.run();
            }
        }
        requireProcessor();
        Object parameter = invocation.getArgs()[1];
        if (statement.isUseCache()) {
            BoundSql source = statement.getBoundSql(parameter);
            invocation.getArgs()[0] = copyBoundSql(statement, source, source.getSql());
        }
        try {
            Object result = invocation.proceed();
            if (result == null) return null;
            if (checkIfStrictMode() || (interceptorEnabled(statement.getId()) && getRevealEnabled())) processor.process(result);
            else processor.decrypt(result);
            return result;
        } finally {
            ((Executor) invocation.getTarget()).clearLocalCache();
        }
    }

    boolean getRevealEnabled() {
        boolean flag = true;
        try {
            // 生产接入时在此赋值：flag = commonddcache.getRevealEnabled();
        } catch (Exception e) {
            log.warn("Failed to read reveal enabled; using default true", e);
        }
        return flag;
    }

    Set<String> getPlaintextRoles() {
        String roles = "001,002,003";
        try {
            // 生产接入时在此赋值：roles = commonddcache.getAddressBookPlaintextRoles();
            return parseRoles(roles);
        } catch (Exception e) {
            log.warn("Failed to read AddressBook plaintext roles; using defaults", e);
        }
        return parseRoles("001,002,003");
    }

    private static Set<String> parseRoles(String configuredRoles) {
        Set<String> roles = new LinkedHashSet<>();
        if (configuredRoles == null || configuredRoles.trim().isEmpty()) return roles;
        for (String item : configuredRoles.split(",", -1)) {
            String role = item.trim();
            if (!role.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("Invalid AddressBook plaintext role");
            }
            roles.add(role);
        }
        return roles;
    }

    boolean checkIfStrictMode(){
        //use commonDDCache.getValueByType("UseStrictMode")
        String mockResult = "1";
        return mockResult.equals("1");
    }

    private Object interceptAddressBook(Invocation invocation, MappedStatement statement)
            throws Throwable {
        requireAddressBook();
        Runnable restore = addressBook.encryptForExecution(statement, invocation.getArgs()[1]);
        try {
            invocation.getArgs()[0] = addressBook.prepare(statement, invocation.getArgs()[1]);
            if (statement.getSqlCommandType() != SqlCommandType.SELECT) return invocation.proceed();
            Object result = invocation.proceed();
            if (result != null) {
                boolean revealEnabled = getRevealEnabled();
                boolean strictMode = checkIfStrictMode();
                Set<String> plaintextRoles = revealEnabled && !strictMode
                        ? getPlaintextRoles() : Collections.emptySet();
                addressBook.processResult(result, revealEnabled, strictMode, plaintextRoles);
            }
            return result;
        } finally {
            restore.run();
            if (statement.getSqlCommandType() == SqlCommandType.SELECT) {
                ((Executor) invocation.getTarget()).clearLocalCache();
            }
        }
    }

    private void requireProcessor() {
        if (processor == null) {
            throw new IllegalStateException("AESInterceptor sensitive dependencies are not configured");
        }
    }

    private void requireAddressBook() {
        if (addressBook == null) {
            throw new IllegalStateException("AESInterceptor sensitive dependencies are not configured");
        }
    }

    private static boolean interceptorEnabled(String statementId) {
        Object configured = Constant.flagMap.get("interceptorItems");
        List<String> items = Arrays.asList(Constant.INTERCEPTOR_ITEMS.split(","));
        if (!Constant.flagMap.isEmpty() && configured != null) {
            items = JSON.parseObject(configured.toString(),
                    new TypeReference<ArrayList<String>>() {});
        }
        if (items == null) return false;
        for (String item : items) {
            if (!item.isEmpty() && statementId.contains(item)) return true;
        }
        return false;
    }

    static MappedStatement copy(MappedStatement statement, Object parameter, String sql) {
        return copyBoundSql(statement, statement.getBoundSql(parameter), sql);
    }

    static MappedStatement copyBoundSql(MappedStatement statement, BoundSql source, String sql) {
        if (sql.equals(source.getSql()) && !statement.isUseCache()) return statement;
        BoundSql boundSql = new BoundSql(statement.getConfiguration(), sql,
                source.getParameterMappings(), source.getParameterObject());
        for (ParameterMapping mapping : source.getParameterMappings()) {
            String property = mapping.getProperty();
            if (source.hasAdditionalParameter(property)) {
                boundSql.setAdditionalParameter(property, source.getAdditionalParameter(property));
            }
        }
        MappedStatement.Builder builder = new MappedStatement.Builder(statement.getConfiguration(),
                statement.getId(), new FixedSqlSource(boundSql), statement.getSqlCommandType());
        builder.resource(statement.getResource());
        builder.fetchSize(statement.getFetchSize());
        builder.statementType(statement.getStatementType());
        builder.keyGenerator(statement.getKeyGenerator());
        joined(statement.getKeyProperties(), builder::keyProperty);
        joined(statement.getKeyColumns(), builder::keyColumn);
        joined(statement.getResultSets(), builder::resultSets);
        builder.timeout(statement.getTimeout());
        builder.parameterMap(statement.getParameterMap());
        // Includes MyBatis's inline ResultMap for XML resultType, with its original handlers.
        builder.resultMaps(statement.getResultMaps());
        builder.resultSetType(statement.getResultSetType());
        builder.cache(statement.getCache());
        builder.flushCacheRequired(statement.isFlushCacheRequired());
        builder.useCache(false);
        builder.resultOrdered(statement.isResultOrdered());
        builder.databaseId(statement.getDatabaseId());
        builder.lang(statement.getLang());
        return builder.build();
    }

    private static void joined(String[] values, Consumer<String> setter) {
        if (values != null && values.length > 0) setter.accept(String.join(",", values));
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {}

    private static Set<String> statements(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static final class FixedSqlSource implements SqlSource {
        private final BoundSql boundSql;
        private FixedSqlSource(BoundSql boundSql) { this.boundSql = boundSql; }
        @Override public BoundSql getBoundSql(Object parameterObject) { return boundSql; }
    }
}
