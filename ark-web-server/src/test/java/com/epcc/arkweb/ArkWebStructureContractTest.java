package com.epcc.arkweb;

import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import com.epcc.arkweb.web.llm.AgentController;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;

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
    private static final Path WORKSPACE = Paths.get("..");

    @Test
    public void localApplicationUsesTheProductionRootPackage() throws Exception {
        String source = source("com/epcc/arkweb/Application.java");
        assertThat(source)
                .contains("package com.epcc.arkweb;")
                .contains("class Application")
                .contains("\"com.epcc.arkweb\"")
                .contains("\"com.union.control\"")
                .contains("@MapperScan(\"com.union.control.mapper\")");
        assertThat(ROOT.resolve("com/union/control")).doesNotExist();
    }

    @Test
    public void allLlmControllersLiveAtTheProductionWebLayer() throws Exception {
        for (String controller : Arrays.asList(
                "LlmController.java", "AgentController.java", "ScheduledTaskController.java")) {
            Path expected = ROOT.resolve("com/epcc/arkweb/web/llm").resolve(controller);
            assertThat(expected).exists();
        }

        List<Path> serviceControllers = Files.walk(WORKSPACE.resolve("service/src/main/java"))
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
                AuthenticatedRequest.class);
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
        String service = Files.walk(WORKSPACE.resolve("service/src/main/java"))
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
        assertThat(ROOT.resolve("com/epcc/arkweb/config/ScheduledExecutionRealm.java")).exists();
    }

    @Test
    public void scheduledIdentityReturnsTheCompleteTrustedSnapshot() throws Exception {
        String source = source("com/epcc/arkweb/web/llm/AgentController.java");

        assertThat(source)
                .contains("data.put(\"userId\", principal.getLoginName())")
                .contains("data.put(\"orgCode\", principal.getOrgCode())")
                .contains("data.put(\"roleId\", principal.getRoleId())");
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
    public void webOwnsAuthenticationAndSchedulingButNotMappers() throws Exception {
        List<String> files = Files.walk(ROOT)
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        assertThat(files).doesNotContain("ScheduledTaskServiceImpl.java");
        assertThat(files).doesNotContain("ApiExceptionHandler.java");
        assertThat(files).doesNotContain("ScheduledTaskMapper.java");
        assertThat(files).doesNotContain("CasSessionFilter.java", "TrustedPrincipal.java");
        assertThat(files).contains(
                "ScheduledTaskScheduler.java", "ScheduledExecutionRealm.java",
                "AuthContextHolder.java", "ShiroUser.java", "ResultMsg.java");
        assertThat(ROOT.resolve("com/epcc/arkweb/schedule/ScheduledTaskScheduler.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/filter/AgentAuthenticationFilter.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/model/AuthenticatedUser.java")).exists();
        assertThat(ROOT.resolve("com/epcc/arkweb/security/CasSessionToken.java")).exists();
    }

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(ROOT.resolve(relative)), StandardCharsets.UTF_8);
    }
}
