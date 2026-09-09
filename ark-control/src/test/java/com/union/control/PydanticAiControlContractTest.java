package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.impl.RunningAnalysisMockServiceImpl;
import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class PydanticAiControlContractTest {
    @Test
    public void springSelectsTheRunningAnalysisProductionConstructor() {
        java.lang.reflect.Constructor<?>[] constructors = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(RunningAnalysisMockServiceImpl.class,
                        "runningAnalysisMockService");

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(ObjectMapper.class);
    }

    @Test
    public void sharedRequestUtilitiesStayOutsideTheServicePackage() {
        Path root = Paths.get("src/main/java/com/union/control");

        assertThat(root.resolve("utils/AgentSupport.java")).exists();
        assertThat(root.resolve("service/AgentSupport.java")).doesNotExist();
        assertThat(root.resolve("service/ServiceExceptions.java")).doesNotExist();
    }

    @Test
    public void serviceModuleHasNoAuthenticationRuntime() throws Exception {
        Path root = Paths.get("src/main/java");
        StringBuilder sources = new StringBuilder();
        Files.walk(root).filter(path -> path.toString().endsWith(".java")).forEach(path -> {
            try {
                sources.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        assertThat(sources.toString())
                .doesNotContain("org.apache.shiro")
                .doesNotContain("AuthContextHolder")
                .doesNotContain("currentUserId()")
                .doesNotContain("@RequiresPermissions");
    }
}
