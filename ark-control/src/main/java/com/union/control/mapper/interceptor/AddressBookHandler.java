package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlCommandType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/** The only statement-specific branch: AddressBook migration and row visibility. */
@Component
final class AddressBookHandler {
    private static final Pattern OLD_ADDRESS_BOOK =
            Pattern.compile("(?<!\\S)t_m_announce_address_book(?!_)");

    private final SensitiveRevealProcessor revealProcessor;

    AddressBookHandler(SensitiveRevealProcessor revealProcessor) {
        if (revealProcessor == null) throw new IllegalArgumentException("Reveal processor is required");
        this.revealProcessor = revealProcessor;
    }

    Runnable encryptForExecution(MappedStatement statement, Object parameter) throws CheckException {
        return statement.getSqlCommandType() == SqlCommandType.SELECT
                ? revealProcessor.encryptQueryParameters(parameter)
                : revealProcessor.encryptForExecution(parameter);
    }

    MappedStatement prepare(MappedStatement statement, Object parameter) {
        BoundSql source = statement.getBoundSql(parameter);
        return AESInterceptor.copyBoundSql(statement, source,
                OLD_ADDRESS_BOOK.matcher(source.getSql())
                        .replaceAll("t_m_announce_address_book_new"));
    }

    void processResult(Object result, boolean revealEnabled, boolean strictMode,
                       Set<String> plaintextRoles) throws CheckException {
        if (strictMode) {
            revealProcessor.process(result);
            return;
        }
        if (!revealEnabled) {
            revealProcessor.decrypt(result);
            return;
        }

        revealProcessor.process(result, row -> allowPlaintext(row, plaintextRoles));
    }

    private boolean allowPlaintext(Object row, Set<String> plaintextRoles) {
        if (row == null) return false;
        try {
            Object value;
            if (row instanceof Map<?, ?>) {
                Map<?, ?> values = (Map<?, ?>) row;
                // Production uses docking_type; role is retained for the local demo contract.
                String key = values.containsKey("dockingType") ? "dockingType"
                        : values.containsKey("docking_type") ? "docking_type" : "role";
                value = values.get(key);
            } else {
                MetaObject metaObject = SystemMetaObject.forObject(row);
                String property = metaObject.hasGetter("dockingType") ? "dockingType" : "role";
                if (!metaObject.hasGetter(property)) return false;
                value = metaObject.getValue(property);
            }
            return value != null && plaintextRoles.contains(value.toString().trim());
        } catch (RuntimeException error) {
            return false;
        }
    }

}
