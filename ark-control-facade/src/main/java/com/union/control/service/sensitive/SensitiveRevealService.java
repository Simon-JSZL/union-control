package com.union.control.service.sensitive;

public interface SensitiveRevealService {
    String reveal(String input);

    class InvalidTokenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    class ExpiredTokenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    class RevealDecryptionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    class StoreUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
