package com.epcc.arkweb;

import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import com.epcc.arkweb.web.llm.AgentController;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.epcc.arkweb.web.llm.AgentAuthorizationInterceptor;
import com.epcc.arkweb.web.llm.AgentPermission;
import org.springframework.web.bind.annotation.PostMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class ArkWebStructureContractTest {

    private static final Path ROOT = Paths.get("src/main/java");
    private static final Path LOCAL_MOCK = Paths.get("src/local-mock/java");
    private static final Path PRODUCTION_OVERLAY = Paths.get("src/production-overlay/java");
    private static final Path WORKSPACE = Paths.get("..");

    @Test
    public void localApplicationUsesTheProductionRootPackage() throws Exception {
        String source = source("com/epcc/arkweb/Application.java");
        assertThat(source)
                .contains("package com.epcc.arkweb;")
                .contains("class Application")
                .contains("@ImportResource(\"classpath:dubbo-consumer.xml\")")
                .doesNotContain("MapperScan")
                .doesNotContain("com.union.control.mapper");
        assertThat(ROOT.resolve("com/union/control")).doesNotExist();
    }

    @Test
    public void allLlmControllersLiveAtTheProductionWebLayer() throws Exception {
        for (String controller : Arrays.asList(
                "LlmController.java", "AgentController.java", "ScheduledTaskController.java")) {
            Path expected = ROOT.resolve("com/epcc/arkweb/web/llm").resolve(controller);
            assertThat(expected).exists();
        }

        List<Path> serviceControllers = Files.walk(WORKSPACE.resolve("ark-control/src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                .collect(Collectors.toList());
        assertThat(serviceControllers).isEmpty();

    }

    @Test
    public void springSelectsTheAgentControllerProductionConstructor() {
        java.lang.reflect.Constructor<?>[] constructors = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(AgentController.class, "agentController");
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(
                ConversationService.class, AgentExecutionService.class,
                MemoryStoreService.class, RunningAnalysisMockService.class,
                AuthenticatedRequest.class, AgentAuthorizationInterceptor.class);
    }

    @Test
    public void controllerMatchesProductionShiroAndRouteConventions() throws Exception {
        String source = source("com/epcc/arkweb/web/llm/ScheduledTaskController.java");

        assertThat(source)
                .contains("package com.epcc.arkweb.web.llm;")
                .contains("@RequestMapping(value = {\"llm\", \"union-op/llm\"})")
                .contains("@RequiresPermissions(value = \"/assistantManager/page\")")
                .contains("AuthenticatedRequest")
                .contains("request.json(")
                .doesNotContain("@RequestHeader")
                .doesNotContain("HttpHeaders.COOKIE")
                .doesNotContain("CASSESSIONID")
                .doesNotContain("agent:execute")
                .doesNotContain("ScheduledExecutionToken");
    }

    @Test
    public void authenticationLivesInWebAndNotInService() throws Exception {
        String service = Files.walk(WORKSPACE.resolve("ark-control/src/main/java"))
                .filter(Files::isRegularFile)
                .map(path -> {
                    try {
                        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                })
                .collect(Collectors.joining("\n"));

        assertThat(service)
                .doesNotContain("org.apache.shiro")
                .doesNotContain("AuthContextHolder")
                .doesNotContain("currentUserId()");
        assertThat(ROOT.resolve("com/epcc/arkweb/helper/AuthenticatedRequest.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/ShiroConfig.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/Realm.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/filter/LoginFormFilter.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/LocalCasRealm.java")).doesNotExist();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/LocalShiroConfig.java")).doesNotExist();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/ArkRealm.java")).doesNotExist();
        assertThat(ROOT.resolve("com/epcc/arkweb/filter/AgentAuthenticationFilter.java")).doesNotExist();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/ScheduledExecutionRealm.java")).doesNotExist();
        assertThat(ROOT.resolve("com/epcc/arkweb/config/AgentAuthorizationConfig.java"))
                .doesNotExist();
        assertThat(source("com/epcc/arkweb/config/InterceptorConfig.java"))
                .contains("addPathPatterns(\"/agent/**\")")
                .doesNotContain("SecurityManager");
        assertThat(LOCAL_MOCK.resolve("com/epcc/arkweb/mock/ArkAuthServiceMock.java"))
                .exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/local")).doesNotExist();
    }

    @Test
    public void productionShiroCopiesWhitelistAgentBeforeTheCatchAll() throws Exception {
        for (String name : Arrays.asList("CasShiroConfig.java", "ShiroConfig.java")) {
            Path file = PRODUCTION_OVERLAY.resolve("com/epcc/arkweb/config").resolve(name);
            assertThat(file).exists();
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            int agent = source.indexOf("filterMap.put(\"/agent/**\", \"anon\")");
            int catchAll = source.indexOf("filterMap.put(\"/**\",\"authc\")");
            if (catchAll < 0)
                catchAll = source.indexOf("filterMap.put(\"/**\", \"authc\")");
            assertThat(agent).as(name).isGreaterThanOrEqualTo(0);
            assertThat(agent).as(name).isLessThan(catchAll);
            assertThat(source).doesNotContain("ScheduledExecutionRealm")
                    .doesNotContain("ScheduledExecutionFilter");
        }

        String interceptors = new String(Files.readAllBytes(PRODUCTION_OVERLAY.resolve(
                "com/epcc/arkweb/config/InterceptorConfig.java")), StandardCharsets.UTF_8);
        assertThat(interceptors)
                .contains("registry.addInterceptor(agentAuthorizationInterceptor)")
                .contains("addPathPatterns(\"/agent/**\")")
                .contains("\"/logout\", \"/agent/**\"")
                .contains("\"/healthcheck.html\", \"/logout\", \"/agent/**\"");
    }

    @Test
    public void scheduledAuthorizationReturnsTheCompleteTrustedSnapshot() throws Exception {
        String source = source("com/epcc/arkweb/web/llm/AgentController.java");

        assertThat(source)
                .contains("@RequestMapping(\"/agent\")")
                .contains("@PostMapping(\"/scheduledTaskAuthorize\")")
                .contains("data.put(\"trustedContext\", authorization)")
                .contains("new LinkedHashMap<>(context)")
                .contains("this.authorization.authorize(");
    }

    @Test
    public void everyAgentToolDeclaresItsPermission() {
        for (java.lang.reflect.Method method : AgentController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) == null
                    || method.getName().equals("scheduledTaskAuthorize")) continue;
            AgentPermission permission = method.getAnnotation(AgentPermission.class);
            assertThat(permission).as(method.getName()).isNotNull();
            assertThat(permission.value()).as(method.getName())
                    .isEqualTo("/assistantManager/page");
        }
    }

    @Test
    public void browserCommandsCannotDeserializeTrustedIdentity() throws Exception {
        String command = source("com/epcc/arkweb/vo/llm/ScheduledTaskCommandVO.java");
        String query = source("com/epcc/arkweb/vo/llm/ScheduledTaskQueryVO.java");

        for (String forbidden : Arrays.asList("userId", "loginName", "orgCode",
                "roleId", "permission", "executionToken", "cookie")) {
            assertThat(command).doesNotContain(forbidden);
            assertThat(query).doesNotContain(forbidden);
        }
    }

    @Test
    public void webOwnsAuthenticationButNotSchedulingOrMappers() throws Exception {
        List<String> files = Files.walk(ROOT)
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        assertThat(files).doesNotContain("ScheduledTaskServiceImpl.java");
        assertThat(files).doesNotContain("ApiExceptionHandler.java");
        assertThat(files).doesNotContain("ScheduledTaskMapper.java");
        assertThat(files).doesNotContain("CasSessionFilter.java", "TrustedPrincipal.java",
                "AgentAuthenticationFilter.java", "LocalCasRealm.java", "CasSessionToken.java",
                "AuthenticatedUser.java");
        assertThat(files).contains(
                "AuthContextHolder.java", "ShiroUser.java", "ResultMsg.java",
                "ShiroConfig.java", "Realm.java", "LoginFormFilter.java",
                "InterceptorConfig.java", "AgentAuthorizationInterceptor.java",
                "AgentPermission.java");
        assertThat(ROOT.resolve("com/epcc/arkweb/schedule/ScheduledTaskScheduler.java")).doesNotExist();
        Path formerSecurityPackage = ROOT.resolve("com/epcc/arkweb/security");
        assertThat(!Files.exists(formerSecurityPackage)
                || Files.walk(formerSecurityPackage).noneMatch(Files::isRegularFile)).isTrue();
    }

    @Test
    public void webDependsOnTheFacadeAndConsumesDubboReferences() throws Exception {
        String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);
        String dubbo = new String(Files.readAllBytes(
                Paths.get("src/main/resources/dubbo-consumer.xml")), StandardCharsets.UTF_8);

        assertThat(pom).contains("<artifactId>ark-control-facade</artifactId>")
                .doesNotContain("<artifactId>ark-control</artifactId>");
        assertThat(dubbo)
                .contains("id=\"agentProxyService\" interface=\"com.union.control.service.AgentProxyService\" check=\"${dubbo.consumer.check}\" url=\"${dubbo.consumer.direct-url}\" timeout=\"120000\"")
                .contains("url=\"${dubbo.consumer.direct-url}\"")
                .contains("timeout=\"${dubbo.consumer.timeout-ms}\"")
                .contains("retries=\"0\"")
                .doesNotContain("name=\"stream\"")
                .doesNotContain("callback=\"true\"");

        String config = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yml")), StandardCharsets.UTF_8);
        assertThat(config)
                .contains("direct-url: ${DUBBO_DIRECT_URL:}")
                .contains("timeout-ms: ${DUBBO_CONSUMER_TIMEOUT_MS:10000}");

        String controller = source("com/epcc/arkweb/web/llm/LlmController.java");
        assertThat(controller)
                .contains("pyAppBaseUrl + \"/agent/v1/runs\"")
                .contains("response.getOutputStream().flush()")
                .doesNotContain("gateway.stream(");
    }

    @Test
    public void webDoesNotLogDubboArgumentsOrStreamChunks() throws Exception {
        String source = Files.walk(ROOT)
                .filter(Files::isRegularFile)
                .map(path -> {
                    try {
                        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                })
                .collect(Collectors.joining("\n"));

        assertThat(source)
                .doesNotContain("invocation.getArguments()")
                .doesNotContain("rpc.getArguments()")
                .doesNotContain("callback chunk");
    }

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(ROOT.resolve(relative)), StandardCharsets.UTF_8);
    }
}
