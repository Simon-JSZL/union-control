package com.epcc.arkweb.web.sensitive.reveal;

import com.epcc.arkweb.ShiroTestSupport;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.code.kaptcha.Producer;
import com.union.control.service.SensitiveDataDemoService;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.SimpleSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class SensitiveRevealControllerTest {
    private static final String ROOT = "/api/sensitive/reveal";
    private static final String TOKEN = "rt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String OTHER_TOKEN = "rt_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private final Producer producer = mock(Producer.class);
    private final SensitiveRevealService reveal = mock(SensitiveRevealService.class);
    private final SensitiveDataDemoService demo = mock(SensitiveDataDemoService.class);
    private final Clock clock = mock(Clock.class);
    private SensitiveCaptchaService captcha;
    private MockMvc mvc;

    @Before
    public void setUp() {
        ShiroTestSupport.bindLocalUser();
        when(producer.createText()).thenReturn("A7K9");
        when(producer.createImage(anyString())).thenReturn(new BufferedImage(110, 40,
                BufferedImage.TYPE_INT_RGB));
        when(clock.millis()).thenReturn(1000L);
        when(reveal.reveal(anyString())).thenReturn("13800138000");
        captcha = new SensitiveCaptchaService(producer, clock);
        mvc = mvc(true);
    }

    @After
    public void tearDown() { ShiroTestSupport.clear(); }

    @Test
    public void normalModeKeepsDirectRevealAndDoesNotGenerateACaptcha() throws Exception {
        mvc = mvc(false);
        mvc.perform(get(ROOT + "/options")).andExpect(jsonPath("$.strictMode").value(false));
        submit(TOKEN, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("13800138000"));
        mvc.perform(post(ROOT + "/captcha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isConflict());
        verifyZeroInteractions(producer);
    }

    @Test
    public void strictModeReturnsPngAndOnlyCallsControlAfterCorrectAnswer() throws Exception {
        mvc.perform(get(ROOT + "/options")).andExpect(jsonPath("$.strictMode").value(true));
        byte[] png = generate();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(110);
        assertThat(image.getHeight()).isEqualTo(40);
        verifyZeroInteractions(reveal);

        submit(TOKEN, " a7k9 ").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value").value("13800138000"));
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(reveal).reveal(input.capture());
        assertThat(input.getValue()).contains(TOKEN, "user-1")
                .doesNotContain("captchaCode").doesNotContain("a7k9");
        submit(TOKEN, "A7K9").andExpect(status().isForbidden());
        verifyNoMoreInteractions(reveal);
    }

    @Test
    public void missingWrongAndExpiredAnswersNeverReachControl() throws Exception {
        submit(TOKEN, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));
        generate();
        submit(TOKEN, "wrong").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
        submit(TOKEN, "A7K9").andExpect(status().isForbidden());
        generate();
        when(clock.millis()).thenReturn(121000L);
        submit(TOKEN, "A7K9").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPTCHA_EXPIRED"));
        verifyZeroInteractions(reveal);
    }

    @Test
    public void captchaCannotBeUsedForAnotherTokenOrSession() throws Exception {
        generate();
        submit(OTHER_TOKEN, "A7K9").andExpect(status().isForbidden());
        submit(TOKEN, "A7K9").andExpect(status().isForbidden());
        generate();
        ShiroTestSupport.bindLocalUser();
        submit(TOKEN, "A7K9").andExpect(status().isForbidden());
        verifyZeroInteractions(reveal);
    }

    @Test
    public void refreshingOverwritesThePreviousChallenge() throws Exception {
        generate();
        when(producer.createText()).thenReturn("B8M2");
        generate();
        submit(TOKEN, "A7K9").andExpect(status().isForbidden());
        generate();
        submit(TOKEN, "B8M2").andExpect(status().isOk());
        verify(reveal, times(1)).reveal(anyString());
    }

    @Test
    public void invalidTokenDoesNotGenerateAnImage() throws Exception {
        mvc.perform(post(ROOT + "/captcha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        verifyZeroInteractions(producer, reveal);
    }

    @Test
    public void anonymousRequestsCannotGenerateAnImage() throws Exception {
        SecurityUtils.getSubject().logout();
        assertThatThrownBy(() -> generate()).hasRootCauseInstanceOf(
                org.apache.shiro.authz.UnauthenticatedException.class);
        verifyZeroInteractions(producer, reveal);
    }

    @Test
    public void everyBrowserEndpointRetainsTheExistingPermission() {
        for (Method method : SensitiveRevealController.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null) continue;
            assertThat(method.getAnnotation(RequiresPermissions.class).value())
                    .containsExactly("/assistantManager/page");
        }
    }

    @Test
    public void changingUserWithinASessionInvalidatesTheAnswer() {
        Session session = new SimpleSession();
        captcha.generate(session, "user-1", TOKEN);
        assertThatThrownBy(() -> captcha.verify(session, "user-2", TOKEN, "A7K9"))
                .isInstanceOf(SensitiveCaptchaService.CaptchaException.class);
    }

    @Test
    public void actualKaptchaProducesADecodablePngAndTheAnswerIsVerifiable() throws Exception {
        Producer real = spy(new com.epcc.arkweb.mock.LocalKaptchaConfiguration().captchaProducer());
        doReturn("A7K9").when(real).createText();
        SensitiveCaptchaService service = new SensitiveCaptchaService(real, clock);
        Session session = new SimpleSession();

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(service.generate(session, "user-1", TOKEN)));

        assertThat(image.getWidth()).isEqualTo(110);
        assertThat(image.getHeight()).isEqualTo(40);
        service.verify(session, "user-1", TOKEN, "A7K9");
    }

    @Test
    public void pngFailureDoesNotLeaveTheOldChallengeUsable() throws Exception {
        generate();
        when(producer.createImage(anyString())).thenThrow(new IllegalStateException("internal image failure"));
        mvc.perform(post(ROOT + "/captcha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CAPTCHA_UNAVAILABLE"));
        submit(TOKEN, "A7K9").andExpect(status().isForbidden());
        verifyZeroInteractions(reveal);
    }

    @Test
    public void sessionChallengeSurvivesSerializationAndIsConsumedOnceUnderConcurrency() throws Exception {
        SimpleSession session = new SimpleSession();
        captcha.generate(session, "user-1", TOKEN);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(session);
        }
        final Session restored;
        try (java.io.ObjectInputStream input = new java.io.ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Session) input.readObject();
        }
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> attempt = () -> {
            start.await();
            try {
                captcha.verify(restored, "user-1", TOKEN, "A7K9");
                return 1;
            } catch (SensitiveCaptchaService.CaptchaException expected) {
                return 0;
            }
        };
        try {
            java.util.concurrent.Future<Integer> first = threads.submit(attempt);
            java.util.concurrent.Future<Integer> second = threads.submit(attempt);
            start.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    + second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void savesReturnExactMaskedRowsWithoutClientRevealAndOverwriteIdentity() throws Exception {
        java.util.Map<String, Object> saved = new java.util.LinkedHashMap<>();
        saved.put("id", 7); saved.put("phoneNumber", "[#138****8000#VIEW:" + TOKEN + "]");
        when(demo.save(anyString())).thenReturn(saved);
        when(demo.saveAddressBook(anyString())).thenReturn(saved);
        for (String path : new String[]{"/demo/save", "/demo/address-book/save"}) {
            mvc.perform(post(ROOT + path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":7,\"userId\":\"forged\",\"phoneNumber\":\"new text\"}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.id").value(7))
                    .andExpect(jsonPath("$.phoneNumber").value(saved.get("phoneNumber")));
        }
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(demo).save(input.capture());
        assertThat(input.getValue()).contains("user-1").doesNotContain("forged");
        verifyZeroInteractions(reveal, producer);
    }

    @Test
    public void expiredSaveTokenHasARefreshableStatusWithoutPlaintext() throws Exception {
        when(demo.save(anyString())).thenThrow(new SensitiveRevealService.ExpiredTokenException());
        mvc.perform(post(ROOT + "/demo/save").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyZeroInteractions(reveal, producer);
    }

    private MockMvc mvc(boolean strict) {
        return MockMvcBuilders.standaloneSetup(new SensitiveRevealController(reveal,
                demo, new AuthenticatedRequest(new ObjectMapper()),
                captcha, strict)).build();
    }

    private byte[] generate() throws Exception {
        return mvc.perform(post(ROOT + "/captcha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private org.springframework.test.web.servlet.ResultActions submit(String token, String answer)
            throws Exception {
        return mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\""
                        + (answer == null ? "" : ",\"captchaCode\":\"" + answer + "\"") + "}"));
    }
}
