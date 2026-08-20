package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.service.sensitive.AddressBookPlaintextPolicy;
import com.union.control.service.sensitive.SensitiveFieldCodec;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only statement-specific branch: AddressBook migration and row visibility. */
public final class AddressBookHandler {
    private static final Pattern OLD_ADDRESS_BOOK =
            Pattern.compile("(?<!\\S)t_m_announce_address_book(?!_)");

    private final SensitiveFieldCodec fieldCodec;
    private final SensitiveRevealProcessor revealProcessor;
    private final AddressBookPlaintextPolicy plaintextPolicy;
    private final MappedStatementCopier statementCopier = new MappedStatementCopier();

    public AddressBookHandler(SensitiveFieldCodec fieldCodec,
                              SensitiveRevealProcessor revealProcessor,
                              AddressBookPlaintextPolicy plaintextPolicy) {
        if (fieldCodec == null) throw new IllegalArgumentException("Field codec is required");
        if (plaintextPolicy == null) {
            throw new IllegalArgumentException("AddressBook plaintext policy is required");
        }
        this.fieldCodec = fieldCodec;
        this.revealProcessor = revealProcessor;
        this.plaintextPolicy = plaintextPolicy;
    }

    MappedStatement prepare(MappedStatement statement, Object parameter) throws CheckException {
        fieldCodec.encrypt(parameter);
        BoundSql boundSql = statement.getBoundSql(parameter);
        return statementCopier.rewriteAndDisableCache(statement, parameter,
                rewriteAddressBookTable(boundSql.getSql()));
    }

    void validate(boolean revealEnabled) {
        if (revealEnabled && revealProcessor == null) {
            throw new IllegalStateException("Sensitive reveal processor is required");
        }
    }

    void processResult(Object result, boolean revealEnabled) throws CheckException {
        if (!revealEnabled) {
            fieldCodec.decrypt(result);
            return;
        }

        List<Object> plaintextRows = new ArrayList<>();
        List<Object> protectedRows = new ArrayList<>();
        for (Object row : rows(result)) {
            if (plaintextPolicy.allowPlaintext(row)) plaintextRows.add(row);
            else protectedRows.add(row);
        }
        fieldCodec.decrypt(plaintextRows);
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

    private static String rewriteAddressBookTable(String sql) {
        Matcher matcher = OLD_ADDRESS_BOOK.matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, "t_m_announce_address_book_new");
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
}
