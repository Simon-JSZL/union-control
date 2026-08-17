package com.union.control.sensitive.reveal;

public class StoreUnavailableException extends RuntimeException {
    public StoreUnavailableException() { super("Reveal token store unavailable"); }
    public StoreUnavailableException(Throwable cause) {
        super("Reveal token store unavailable", cause);
    }
}
