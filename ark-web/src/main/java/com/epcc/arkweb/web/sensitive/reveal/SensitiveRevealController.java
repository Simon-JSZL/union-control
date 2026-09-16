package com.epcc.arkweb.web.sensitive.reveal;

import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.service.SensitiveDataDemoService;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/sensitive/reveal")
public class SensitiveRevealController {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealController.class);
    private final SensitiveRevealService service;
    private final SensitiveDataDemoService demoService;
    private final AuthenticatedRequest request;
    private final SensitiveCaptchaService captcha;
    private final boolean strictMode;

    public SensitiveRevealController(SensitiveRevealService service,
            SensitiveDataDemoService demoService, AuthenticatedRequest request,
            SensitiveCaptchaService captcha,
            @Value("${sensitive.reveal.strict_mode:false}") boolean strictMode) {
        this.service = service;
        this.demoService = demoService;
        this.request = request;
        this.captcha = captcha;
        this.strictMode = strictMode;
    }

    @GetMapping("/options")
    @RequiresPermissions(value = "/assistantManager/page")
    public ResponseEntity<Map<String, Boolean>> options() {
        request.actor();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Collections.singletonMap("strictMode", strictMode));
    }

    @PostMapping("/captcha")
    @RequiresPermissions(value = "/assistantManager/page")
    public ResponseEntity<byte[]> captcha(@RequestBody Map<String, Object> payload) {
        String userId = request.actor().getLoginName();
        if (!strictMode) {
            LOG.info("Sensitive captcha request rejected reason=strict_mode_disabled");
            throw new SensitiveCaptchaService.CaptchaException("STRICT_MODE_DISABLED", HttpStatus.CONFLICT);
        }
        byte[] png = captcha.generate(SecurityUtils.getSubject().getSession(false),
                userId, string(payload, "token"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping
    @RequiresPermissions(value = "/assistantManager/page")
    public ResponseEntity<Map<String, String>> reveal(@RequestBody Map<String, Object> payload) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        String stage = "captcha";
        LOG.info("Sensitive reveal received request_id={} strict_mode={} session_ref={}", requestId, strictMode,
                SensitiveCaptchaService.sessionRef(SecurityUtils.getSubject().getSession(false)));
        try {
            if (strictMode) {
                captcha.verify(SecurityUtils.getSubject().getSession(false), request.actor().getLoginName(),
                        string(payload, "token"), string(payload, "captchaCode"));
            }
            stage = "control";
            LOG.info("Sensitive reveal calling Control request_id={}", requestId);
            // Only token and server-generated trace metadata cross Dubbo; the answer stays in Web.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("token", payload.get("token"));
            input.put("revealRequestId", requestId);
            String plaintext = service.reveal(request.json(input));
            LOG.info("Sensitive reveal completed request_id={} elapsed_ms={}",
                    requestId, (System.nanoTime() - started) / 1000000);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(Collections.singletonMap("value", plaintext));
        } catch (RuntimeException error) {
            String reason = error instanceof SensitiveCaptchaService.CaptchaException
                    ? error.getMessage() : error.getClass().getSimpleName();
            LOG.info("Sensitive reveal rejected request_id={} stage={} reason={} elapsed_ms={}",
                    requestId, stage, reason, (System.nanoTime() - started) / 1000000);
            throw error;
        }
    }

    @ExceptionHandler(SensitiveCaptchaService.CaptchaException.class)
    public ResponseEntity<Map<String, String>> captchaError(SensitiveCaptchaService.CaptchaException error) {
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").contentType(MediaType.APPLICATION_JSON)
                .body(Collections.singletonMap("code", error.getMessage()));
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String ? (String) value : null;
    }

    @PostMapping("/demo/save")
    @RequiresPermissions(value = "/assistantManager/page")
    public ResponseEntity<Map<String, Object>> saveDemo(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(demoService.save(request.json(payload)));
    }

    @PostMapping("/demo/address-book/save")
    @RequiresPermissions(value = "/assistantManager/page")
    public ResponseEntity<Map<String, Object>> saveAddressBookDemo(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(demoService.saveAddressBook(request.json(payload)));
    }

    @ExceptionHandler(SensitiveRevealService.ExpiredTokenException.class)
    public ResponseEntity<Map<String, String>> expiredToken(SensitiveRevealService.ExpiredTokenException error) {
        return ResponseEntity.status(HttpStatus.GONE).cacheControl(CacheControl.noStore())
                .body(Collections.singletonMap("code", "TOKEN_EXPIRED"));
    }

    @PostMapping("/demo/insert")
    @RequiresPermissions(value = "/assistantManager/page")
    public Map<String, Integer> insertDemo(@RequestBody Map<String, Object> payload) {
        return Collections.singletonMap("affectedRows", demoService.insert(request.json(payload)));
    }

    @GetMapping("/demo/query")
    @RequiresPermissions(value = "/assistantManager/page")
    public List<Map<String, Object>> queryDemo() {
        return demoService.query(request.json());
    }

    @PostMapping("/demo/address-book/insert")
    @RequiresPermissions(value = "/assistantManager/page")
    public Map<String, Integer> insertAddressBookDemo(
            @RequestBody Map<String, Object> payload) {
        return Collections.singletonMap("affectedRows",
                demoService.insertAddressBook(request.json(payload)));
    }

    @GetMapping("/demo/address-book/query")
    @RequiresPermissions(value = "/assistantManager/page")
    public List<Map<String, Object>> queryAddressBookDemo() {
        return demoService.queryAddressBook(request.json());
    }
}
