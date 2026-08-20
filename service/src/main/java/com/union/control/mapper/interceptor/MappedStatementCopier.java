package com.union.control.mapper.interceptor;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;

final class MappedStatementCopier {
    MappedStatement disableCache(MappedStatement statement, Object parameter) {
        if (!statement.isUseCache()) return statement;
        BoundSql boundSql = statement.getBoundSql(parameter);
        return copy(statement, boundSql, boundSql.getSql(), parameter, false);
    }

    MappedStatement rewriteAndDisableCache(MappedStatement statement, Object parameter,
                                           String sql) {
        BoundSql boundSql = statement.getBoundSql(parameter);
        if (sql.equals(boundSql.getSql()) && !statement.isUseCache()) return statement;
        return copy(statement, boundSql, sql, parameter, false);
    }

    private MappedStatement copy(MappedStatement statement, BoundSql boundSql, String sql,
                                 Object parameter, boolean useCache) {
        BoundSql copiedBoundSql = new BoundSql(statement.getConfiguration(), sql,
                boundSql.getParameterMappings(), parameter);
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            String property = mapping.getProperty();
            if (boundSql.hasAdditionalParameter(property)) {
                copiedBoundSql.setAdditionalParameter(property,
                        boundSql.getAdditionalParameter(property));
            }
        }

        MappedStatement.Builder builder = new MappedStatement.Builder(statement.getConfiguration(),
                statement.getId(), new FixedBoundSqlSource(copiedBoundSql),
                statement.getSqlCommandType());
        builder.resource(statement.getResource());
        builder.fetchSize(statement.getFetchSize());
        builder.statementType(statement.getStatementType());
        builder.keyGenerator(statement.getKeyGenerator());
        setJoined(statement.getKeyProperties(), builder::keyProperty);
        setJoined(statement.getKeyColumns(), builder::keyColumn);
        setJoined(statement.getResultSets(), builder::resultSets);
        builder.timeout(statement.getTimeout());
        builder.parameterMap(statement.getParameterMap());
        builder.resultMaps(statement.getResultMaps());
        builder.resultSetType(statement.getResultSetType());
        builder.cache(statement.getCache());
        builder.flushCacheRequired(statement.isFlushCacheRequired());
        builder.useCache(useCache && statement.isUseCache());
        builder.resultOrdered(statement.isResultOrdered());
        builder.databaseId(statement.getDatabaseId());
        builder.lang(statement.getLang());
        return builder.build();
    }

    private static void setJoined(String[] values, java.util.function.Consumer<String> setter) {
        if (values != null && values.length > 0) setter.accept(String.join(",", values));
    }

    private static final class FixedBoundSqlSource implements SqlSource {
        private final BoundSql boundSql;

        private FixedBoundSqlSource(BoundSql boundSql) {
            this.boundSql = boundSql;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            return boundSql;
        }
    }
}
