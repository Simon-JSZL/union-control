package com.union.control;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class ArkControlStructureContractTest {

    private static final Path JAVA_ROOT = Paths.get("src/main/java");

    @Test
    public void applicationStartsAHeadlessDubboProvider() throws Exception {
        String application = source("com/union/control/ArkControlApplication.java");
        String config = resource("application.yml");

        assertThat(application)
                .contains("class ArkControlApplication")
                .contains("@MapperScan(\"com.union.control.mapper\")")
                .contains("@EnableScheduling")
                .contains("@ImportResource(\"classpath:dubbo-provider.xml\")");
        assertThat(config).contains("web-environment: false");
    }

    @Test
    public void providerPublishesEveryFacadeService() throws Exception {
        String dubbo = resource("dubbo-provider.xml");
        assertThat(dubbo).contains("<dubbo:provider filter=\"providerAccessLog\"");
        for (String service : Arrays.asList(
                "AgentExecutionService",
                "AgentProxyService",
                "ConversationService",
                "MemoryStoreService",
                "RunningAnalysisMockService",
                "ScheduledTaskService",
                "SensitiveDataDemoService")) {
            assertThat(dubbo).contains(
                    "interface=\"com.union.control.service." + service + "\"");
        }
        assertThat(dubbo).contains(
                "interface=\"com.union.control.service.sensitive.SensitiveRevealService\"");

        assertThat(resource("META-INF/dubbo/com.alibaba.dubbo.rpc.Filter"))
                .contains("providerAccessLog=com.union.control.dubbo.ProviderAccessLogFilter");
        assertThat(source("com/union/control/dubbo/ProviderAccessLogFilter.java"))
                .contains("Dubbo provider request started")
                .contains("Dubbo provider request completed")
                .doesNotContain("getArguments()");
    }

    @Test
    public void providerDoesNotExposeTheWebOnlyStream() throws Exception {
        String dubbo = resource("dubbo-provider.xml");
        String facade = new String(Files.readAllBytes(Paths.get(
                "../ark-control-facade/src/main/java/com/union/control/service/AgentProxyService.java")),
                StandardCharsets.UTF_8);

        assertThat(dubbo)
                .doesNotContain("name=\"stream\"")
                .doesNotContain("callback=\"true\"");
        assertThat(facade).doesNotContain("stream(").doesNotContain("AgentStreamCallback");
    }

    @Test
    public void providerContainsNoWebControllerOrShiroBoundary() throws Exception {
        List<Path> javaFiles = Files.walk(JAVA_ROOT)
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());
        String source = javaFiles.stream()
                .map(path -> {
                    try {
                        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                })
                .collect(Collectors.joining("\n"));

        assertThat(javaFiles.stream()
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith("Controller.java"))
                .collect(Collectors.toList())).isEmpty();
        assertThat(source)
                .doesNotContain("org.apache.shiro")
                .doesNotContain("HttpServletRequest")
                .doesNotContain("HttpServletResponse");
        assertThat(JAVA_ROOT.resolve("com/union/control/schedule/ScheduledTaskScheduler.java")).exists();
        assertThat(JAVA_ROOT.resolve("com/union/control/schedule/ScheduledExecutionToken.java")).exists();
    }

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(JAVA_ROOT.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String resource(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources").resolve(relative)),
                StandardCharsets.UTF_8);
    }

}
