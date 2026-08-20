package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.service.sensitive.SensitiveFieldCodec;
import com.union.control.service.sensitive.SensitiveRevealProcessor;

public final class SensitiveResultProcessor {
    private final SensitiveFieldCodec fieldCodec;
    private final SensitiveRevealProcessor revealProcessor;
    private final AddressBookHandler addressBookHandler;

    public SensitiveResultProcessor(SensitiveFieldCodec fieldCodec,
                                    SensitiveRevealProcessor revealProcessor,
                                    AddressBookHandler addressBookHandler) {
        if (fieldCodec == null) throw new IllegalArgumentException("Field codec is required");
        if (addressBookHandler == null) {
            throw new IllegalArgumentException("AddressBook handler is required");
        }
        this.fieldCodec = fieldCodec;
        this.revealProcessor = revealProcessor;
        this.addressBookHandler = addressBookHandler;
    }

    void validate(StatementRoute route, boolean revealEnabled) {
        if (route == StatementRoute.ADDRESS_BOOK) {
            addressBookHandler.validate(revealEnabled);
        } else if (route == StatementRoute.DECRYPT && revealEnabled && revealProcessor == null) {
            throw new IllegalStateException("Sensitive reveal processor is required");
        }
    }

    Object process(StatementRoute route, Object result, boolean revealEnabled)
            throws CheckException {
        if (result == null || !route.hasSensitiveResult()) return result;
        if (route == StatementRoute.ADDRESS_BOOK) {
            addressBookHandler.processResult(result, revealEnabled);
        } else if (revealEnabled) {
            revealProcessor.process(result);
        } else {
            fieldCodec.decrypt(result);
        }
        return result;
    }
}
