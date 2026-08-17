package com.epcc.arkweb;

import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import com.epcc.arkweb.web.llm.AgentController;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.union.control.service.ScheduledTaskService;

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
    public void localApplicationKeepsTheOriginalMainClassAndScansBothModules() throws Exception {
        String source = source("com/union/control/UnionControlApplication.java");
        assertThat(source)
                .contains("class UnionControlApplication")
                .contains("\"com.union.control\"")
                .contains("\"com.epcc.arkweb\"");
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
                ScheduledTaskService.class);
    }

    @Test
    public void controllerMatchesProductionShiroAndRouteConventions() throws Exception {
        String source = source("com/epcc/arkweb/web/llm/ScheduledTaskController.java");

        assertThat(source)
                .contains("package com.epcc.arkweb.web.llm;")
                .contains("@RequestMapping(value = {\"llm\", \"union-op/llm\"})")
                .contains("@RequiresPermissions(value = \"/assistantManager/page\")")
                .contains("AuthContextHolder.getAuthUserDetails()")
                .contains("user.getLoginName()", "user.getOrgCode()", "user.getRoleId()")
                .doesNotContain("@RequestHeader")
                .doesNotContain("HttpHeaders.COOKIE")
                .doesNotContain("CASSESSIONID")
                .doesNotContain("agent:execute")
                .doesNotContain("ScheduledExecutionToken");
    }

    @Test
    public void productionModuleDoesNotOwnShiroOrServiceImplementations() throws Exception {
        String all = Files.walk(ROOT)
                .filter(Files::isRegularFile)
                .map(path -> {
                    try {
                        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                })
                .collect(Collectors.joining("\n"));

        assertThat(all).doesNotContain("extends AuthorizingRealm");
        assertThat(all).doesNotContain("CASSESSIONID");
        assertThat(all).doesNotContain("JdbcTemplate");
        assertThat(all).doesNotContain("@Scheduled");
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
    public void webModuleContainsNoServiceImplementationOrScheduler() throws Exception {
        List<String> files = Files.walk(ROOT)
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        assertThat(files).doesNotContain("ScheduledTaskServiceImpl.java");
        assertThat(files).doesNotContain("ApiExceptionHandler.java");
        assertThat(files).doesNotContain("ScheduledTaskMapper.java");
        assertThat(files).doesNotContain("ScheduledTaskScheduler.java");
        assertThat(files).doesNotContain("ScheduledExecutionRealm.java");
        assertThat(files).doesNotContain("AuthContextHolder.java");
        assertThat(files).doesNotContain("ShiroUser.java");
        assertThat(files).doesNotContain("ResultMsg.java");
    }

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(ROOT.resolve(relative)), StandardCharsets.UTF_8);
    }
}
