package com.union.control.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** One short-lived capability for one scheduled run. The database stores only its hash. */
public final class ScheduledExecutionToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String value;

    private ScheduledExecutionToken(String value) {
        this.value = value;
    }

    public static ScheduledExecutionToken issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new ScheduledExecutionToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public String authorizationHeader() {
        return "Scheduled " + value;
    }

    public String hash() {
        return hashSubmitted(value);
    }

    public static String hashSubmitted(String token) {
        if (!valid(token)) throw new IllegalArgumentException("scheduled token 非法");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean valid(String token) {
        if (token == null || token.length() != 43
                || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            return Base64.getUrlDecoder().decode(token).length == 32;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
