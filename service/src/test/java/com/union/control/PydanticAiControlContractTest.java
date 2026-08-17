package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.local.security.LocalCasRealm;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.service.ConversationService;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
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
    @Before
    public void bindSubject() { ShiroTestSupport.bindLocalUser(); }

    @After
    public void clearSubject() { ShiroTestSupport.clear(); }

    @Test
    public void authenticatedIdentityComesFromTheShiroSubject() {
        ConversationService service = new ConversationService(
                mock(ConversationMapper.class), mock(AgentExecutionMapper.class),
                new ObjectMapper());
        @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>)
                service.userInfo().get("data");
        assertThat(user.get("userId")).isEqualTo(LocalCasRealm.USER_ID);
        assertThat(user.get("orgCode")).isEqualTo(LocalCasRealm.ORG_CODE);
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
                "`role_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL",
                "`result_payload` LONGTEXT",
                "`read_flag` TINYINT(1) NOT NULL DEFAULT 0");
    }

    @Test
    public void scheduledRebuildMigrationMatchesTheCanonicalSchema() throws Exception {
        String schema = source("src/main/resources/schema.sql");
        String migration = source(
                "deploy/sql/20260813_add_scheduled_execution_identity.sql");
        String scheduledTables = schema.substring(schema.indexOf(
                "CREATE TABLE IF NOT EXISTS `agent_scheduled_task`"));

        assertThat(migration).contains(
                "DROP TABLE IF EXISTS `agent_scheduled_task_run`;\n"
                        + "DROP TABLE IF EXISTS `agent_scheduled_task`;")
                .endsWith(scheduledTables);
    }

    @Test
    public void scheduledConfigExposesOnlyOperationalKnobs() throws Exception {
        String yaml = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yml")), StandardCharsets.UTF_8);
        String scheduler = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/scheduled/ScheduledTaskScheduler.java")),
                StandardCharsets.UTF_8);
        String proxy = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/service/AgentProxyService.java")),
                StandardCharsets.UTF_8);

        assertThat(yaml).contains("${SCHEDULED_TASK_MAX_RUN_SECONDS:930}")
                .contains("${SCHEDULED_TASK_WORKER_THREADS:2}")
                .contains("${SCHEDULED_TASK_TOKEN_TTL_SECONDS:960}")
                .doesNotContain("SCHEDULED_TASK_CONNECT_TIMEOUT_MS")
                .doesNotContain("SCHEDULED_TASK_READ_TIMEOUT_MS")
                .doesNotContain("SCHEDULED_TASK_INITIAL_DELAY_MS")
                .doesNotContain("SCHEDULED_TASK_SCAN_INTERVAL_MS")
                .doesNotContain("SCHEDULED_TASK_SCAN_BATCH_SIZE")
                .doesNotContain("SCHEDULED_TASK_WORKER_QUEUE")
                .doesNotContain("SCHEDULED_TASK_MIN_INTERVAL_SECONDS")
                .doesNotContain("SCHEDULED_TASK_LOCAL_DELEGATED_SESSION_ENABLED")
                .doesNotContain("scheduled-local-delegated-session-enabled")
                .doesNotContain("AGENT_CLEANUP_INTERVAL_MS");
        assertThat(scheduler)
                .contains("${agent.scheduled-max-run-seconds:930}")
                .contains("@Scheduled(initialDelay = 1000, fixedDelay = 5000)");
        assertThat(proxy).doesNotContain("scheduledTask").doesNotContain("scheduledHttp");
    }

    @Test
    public void scheduledImplementationFollowsTheProductionLayeredPackages() throws Exception {
        Set<String> files = new HashSet<>();
        Files.list(Paths.get("src/main/java/com/union/control/scheduled"))
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> files.add(path.getFileName().toString()));

        assertThat(files).containsOnly("ScheduledTaskScheduler.java");
        assertThat(Paths.get(
                "src/main/java/com/union/control/mapper/ScheduledTaskMapper.java")).exists();
        assertThat(Paths.get(
                "src/main/java/com/union/control/service/ScheduledTaskService.java")).exists();

        Set<String> securityFiles = new HashSet<>();
        Files.list(Paths.get("src/main/java/com/union/control/security"))
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> securityFiles.add(path.getFileName().toString()));
        assertThat(securityFiles).containsOnly(
                "ScheduledExecutionFilter.java",
                "ScheduledExecutionRealm.java");

        String productionScheduled = String.join("\n", Arrays.asList(
                source("src/main/java/com/union/control/scheduled/ScheduledTaskScheduler.java"),
                source("src/main/java/com/union/control/mapper/ScheduledTaskMapper.java"),
                source("src/main/java/com/union/control/service/ScheduledTaskService.java"),
                source("src/main/java/com/union/control/security/ScheduledExecutionFilter.java"),
                source("src/main/java/com/union/control/security/ScheduledExecutionRealm.java")));
        assertThat(productionScheduled)
                .doesNotContain("com.union.control.local")
                .doesNotContain("LocalAuth")
                .doesNotContain("LocalCasRealm")
                .doesNotContain("RolePermissionProvider")
                .doesNotContain("ShiroConfig")
                .doesNotContain("@Configuration");

        String service = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/service/ScheduledTaskService.java")),
                StandardCharsets.UTF_8);
        String scheduler = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/union/control/scheduled/ScheduledTaskScheduler.java")),
                StandardCharsets.UTF_8);

        assertThat(service).contains("ScheduledTaskMapper")
                .doesNotContain("JdbcTemplate")
                .doesNotContain("LocalAuth")
                .doesNotContain("PermissionProvider");
        assertThat(scheduler).contains("AgentProxyService")
                .doesNotContain("NonStreamRunService")
                .doesNotContain("DelegatedSessionService")
                .doesNotContain("cookieForOwner")
                .doesNotContain("Bearer ");
        assertThat(Paths.get(
                "src/main/java/com/union/control/service/LocalAuth.java")).doesNotExist();
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
                .contains("com.union.control.mapper.ScheduledTaskMapper.findDueTask");
        assertThat(xml).doesNotContain("${");
    }

    @Test
    public void runtimeMappersRemainSeparatedAndParseable() throws Exception {
        for (String name : Arrays.asList(
                "ConversationMapper", "AgentExecutionMapper", "MemoryStoreMapper")) {
            String resource = "mapper/" + name + ".xml";
            Configuration configuration = new Configuration();
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
            String statement = "ConversationMapper".equals(name) ? "findConversations"
                    : "AgentExecutionMapper".equals(name) ? "findExecutions" : "readMemory";
            assertThat(configuration.getMappedStatementNames()).contains(
                    "com.union.control.mapper." + name + "." + statement);
            assertThat(source("src/main/resources/" + resource))
                    .contains("<mapper namespace=", "<select")
                    .doesNotContain("${");
        }
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
