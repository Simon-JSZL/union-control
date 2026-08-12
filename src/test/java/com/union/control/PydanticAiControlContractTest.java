package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.ControlMapper;
import com.union.control.service.ControlService;
import com.union.control.service.LocalAuth;
import org.junit.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class PydanticAiControlContractTest {
    @Test
    public void springSelectsTheCookieAuthenticatedAgentControllerConstructor() {
        java.lang.reflect.Constructor<?>[] constructors = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(
                        com.union.control.controller.AgentController.class, "agentController");
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(ControlService.class);
    }

    @Test
    public void authenticatedIdentityComesFromTheCookie() {
        ControlService service = new ControlService(mock(ControlMapper.class), new ObjectMapper());
        @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>)
                service.userInfo(LocalAuth.cookieHeader()).get("data");
        assertThat(user.get("userId")).isEqualTo(LocalAuth.USER_ID);
        assertThat(user).doesNotContainKey("memoryNamespace");
    }

    @Test
    public void schemaContainsCurrentConversationSensitiveAndScheduledModels() throws Exception {
        String schema = new String(Files.readAllBytes(
                Paths.get("src/main/resources/schema.sql")), StandardCharsets.UTF_8);
        for (String table : Arrays.asList(
                "ai_conversation", "ai_agent_execution", "ai_conversation_message",
                "ai_memory_file", "ai_memory_operation", "sensitive_data_demo",
                "agent_scheduled_task", "agent_scheduled_task_run")) {
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS `" + table + "`");
            String definition = schema.substring(
                    schema.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`"));
            definition = definition.substring(0, definition.indexOf("ENGINE=InnoDB"));
            assertThat(definition).contains("`delete_flag`");
        }
        assertThat(schema).contains(
                "`is_pinned` TINYINT(1) NOT NULL DEFAULT 0",
                "UNIQUE KEY `uk_scheduled_run_occurrence` (`task_id`, `scheduled_at`)",
                "`result_payload` LONGTEXT",
                "`read_flag` TINYINT(1) NOT NULL DEFAULT 0");
    }

    @Test
    public void scheduledConfigHasNoDedicatedTokenOrProxyTimeouts() throws Exception {
        String yaml = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yml")), StandardCharsets.UTF_8);
        String scheduler = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/scheduled/ScheduledTaskScheduler.java")),
                StandardCharsets.UTF_8);
        String proxy = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/service/AgentProxyService.java")),
                StandardCharsets.UTF_8);

        assertThat(yaml).contains("${SCHEDULED_TASK_MAX_RUN_SECONDS:930}")
                .doesNotContain("SCHEDULED_TASK_TOKEN")
                .doesNotContain("SCHEDULED_TASK_CONNECT_TIMEOUT_MS")
                .doesNotContain("SCHEDULED_TASK_READ_TIMEOUT_MS");
        assertThat(scheduler).contains("${agent.scheduled-max-run-seconds:930}");
        assertThat(proxy).doesNotContain("scheduledTask").doesNotContain("scheduledHttp");
    }

    @Test
    public void scheduledFeatureHasOnlyTheFourRequestedLayers() throws Exception {
        Set<String> files = new HashSet<>();
        Files.list(Paths.get("src/main/java/com/union/control/scheduled"))
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> files.add(path.getFileName().toString()));

        assertThat(files).containsOnly(
                "ScheduledTaskController.java",
                "ScheduledTaskScheduler.java",
                "ScheduledTaskService.java",
                "ScheduledTaskMapper.java");

        String service = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/scheduled/ScheduledTaskService.java")),
                StandardCharsets.UTF_8);
        String controller = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/scheduled/ScheduledTaskController.java")),
                StandardCharsets.UTF_8);
        String scheduler = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/scheduled/ScheduledTaskScheduler.java")),
                StandardCharsets.UTF_8);

        assertThat(service).contains("ScheduledTaskMapper").doesNotContain("JdbcTemplate");
        assertThat(controller).doesNotContain("AgentProxyService").doesNotContain("/agent/scheduled");
        assertThat(scheduler).contains("NonStreamRunService")
                .doesNotContain("AgentProxyService").doesNotContain("Bearer ");
    }

    @Test
    public void scheduledMybatisMapperParsesWithoutRawSqlSubstitution() throws Exception {
        String resource = "mapper/ScheduledTaskMapper.xml";
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        String xml = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/mapper/ScheduledTaskMapper.xml")), StandardCharsets.UTF_8);

        assertThat(configuration.getMappedStatementNames())
                .contains("com.union.control.scheduled.ScheduledTaskMapper.findDueTask");
        assertThat(xml).doesNotContain("${");
    }
}
