package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import org.apache.ibatis.mapping.MappedStatement;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    MappedStatement prepare(MappedStatement statement, Object parameter) throws CheckException {
        revealProcessor.encrypt(parameter);
        return AESInterceptor.copy(statement, parameter,
                OLD_ADDRESS_BOOK.matcher(statement.getBoundSql(parameter).getSql())
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

        List<Object> plaintextRows = new ArrayList<>();
        List<Object> protectedRows = new ArrayList<>();
        for (Object row : rows(result)) {
            if (allowPlaintext(row, plaintextRoles)) plaintextRows.add(row);
            else protectedRows.add(row);
        }
        revealProcessor.decrypt(plaintextRows);
        revealProcessor.process(protectedRows);
    }

    private static List<Object> rows(Object result) {
        if (result == null) return Collections.emptyList();
        List<Object> rows = new ArrayList<>();
        if (result instanceof Iterable<?>) {
            for (Object row : (Iterable<?>) result) rows.add(row);
        } else if (result.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(result); i++) rows.add(Array.get(result, i));
        } else {
            rows.add(result);
        }
        return rows;
    }

    private boolean allowPlaintext(Object row, Set<String> plaintextRoles) {
        if (row == null) return false;
        try {
            Object value;
            if (row instanceof Map<?, ?>) {
                value = ((Map<?, ?>) row).get("role");
            } else {
                MetaObject metaObject = SystemMetaObject.forObject(row);
                if (!metaObject.hasGetter("role")) return false;
                value = metaObject.getValue("role");
            }
            return value != null && plaintextRoles.contains(value.toString().trim());
        } catch (RuntimeException error) {
            return false;
        }
    }

}
