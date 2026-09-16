package com.epcc.arkweb.web.sensitive.reveal;

import com.google.code.kaptcha.Producer;
import org.apache.shiro.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** Web-only challenge; uses the existing login session, never the reveal token store. */
@Service
public class SensitiveCaptchaService {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveCaptchaService.class);
    private static final String SESSION_KEY = SensitiveCaptchaService.class.getName() + ".challenge";
    private static final long TTL_MILLIS = 120000L;
    private static final Pattern TOKEN = Pattern.compile("rt_[A-Za-z0-9_-]{43}");
    private final Producer producer;
    private final Clock clock;
    // ponytail: short session updates share a JVM lock; multi-node one-use needs sticky sessions.
    private final Object sessionLock = new Object();

    @Autowired
    public SensitiveCaptchaService(Producer producer) {
        this(producer, Clock.systemUTC());
    }

    SensitiveCaptchaService(Producer producer, Clock clock) {
        this.producer = producer;
        this.clock = clock;
    }

    public byte[] generate(Session session, String userId, String token) {
        long started = System.nanoTime();
        String sessionRef = sessionRef(session);
        if (token == null || !TOKEN.matcher(token).matches()) {
            LOG.info("Sensitive captcha generation rejected session_ref={} reason=invalid_token", sessionRef);
            throw new CaptchaException("INVALID_TOKEN", HttpStatus.BAD_REQUEST);
        }
        if (session == null) {
            LOG.info("Sensitive captcha generation rejected session_ref=none reason=no_session");
            throw new CaptchaException("CAPTCHA_REQUIRED", HttpStatus.FORBIDDEN);
        }
        synchronized (sessionLock) {
            session.removeAttribute(SESSION_KEY);
        }
        String answer;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            answer = producer.createText();
            if (!ImageIO.write(producer.createImage(answer), "png", output)) {
                throw new IOException("PNG encoder unavailable");
            }
        } catch (IOException | RuntimeException error) {
            LOG.info("Sensitive captcha generation failed session_ref={} error_type={}",
                    sessionRef, error.getClass().getSimpleName());
            throw new CaptchaException("CAPTCHA_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Challenge challenge = new Challenge(userId, token, answer, clock.millis() + TTL_MILLIS);
        synchronized (sessionLock) {
            session.setAttribute(SESSION_KEY, challenge);
        }
        LOG.info("Sensitive captcha generated session_ref={} challenge_ref={} ttl_seconds=120 elapsed_ms={}",
                sessionRef, challenge.id, (System.nanoTime() - started) / 1000000);
        return output.toByteArray();
    }

    public void verify(Session session, String userId, String token, String answer) {
        String sessionRef = sessionRef(session);
        Object stored;
        synchronized (sessionLock) {
            stored = session == null ? null : session.removeAttribute(SESSION_KEY);
        }
        if (!(stored instanceof Challenge)) {
            LOG.info("Sensitive captcha verification rejected session_ref={} reason={}",
                    sessionRef, session == null ? "no_session" : "challenge_missing");
            throw new CaptchaException("CAPTCHA_REQUIRED", HttpStatus.FORBIDDEN);
        }
        Challenge challenge = (Challenge) stored;
        if (clock.millis() >= challenge.expiresAt) {
            LOG.info("Sensitive captcha verification rejected session_ref={} challenge_ref={} reason=expired",
                    sessionRef, challenge.id);
            throw new CaptchaException("CAPTCHA_EXPIRED", HttpStatus.FORBIDDEN);
        }
        String reason = !challenge.userId.equals(userId) ? "user_mismatch"
                : !challenge.token.equals(token) ? "token_mismatch"
                : answer == null || answer.trim().isEmpty() ? "answer_missing"
                : answer.length() > 32 || !challenge.answer.equalsIgnoreCase(answer.trim()) ? "answer_incorrect" : null;
        if (reason != null) {
            LOG.info("Sensitive captcha verification rejected session_ref={} challenge_ref={} reason={}",
                    sessionRef, challenge.id, reason);
            throw new CaptchaException("CAPTCHA_INVALID", HttpStatus.FORBIDDEN);
        }
        LOG.info("Sensitive captcha verification passed session_ref={} challenge_ref={}", sessionRef, challenge.id);
    }

    /** A non-credential correlation value; never log the login session ID itself. */
    static String sessionRef(Session session) {
        if (session == null || session.getId() == null) return "none";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(session.getId().toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Challenge implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id = UUID.randomUUID().toString();
        final String userId;
        final String token;
        final String answer;
        final long expiresAt;

        Challenge(String userId, String token, String answer, long expiresAt) {
            this.userId = userId;
            this.token = token;
            this.answer = answer;
            this.expiresAt = expiresAt;
        }
    }

    public static final class CaptchaException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final HttpStatus status;

        CaptchaException(String code, HttpStatus status) {
            super(code);
            this.status = status;
        }
    }
}
