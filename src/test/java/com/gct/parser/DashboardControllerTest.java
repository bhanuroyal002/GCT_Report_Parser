package com.gct.parser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DashboardControllerTest {

    @Autowired
    DashboardController controller;

    @Test
    void dashboardContainsExpectedSuites() {
        var dashboard = controller.dashboard();
        var suites = (java.util.List<?>) dashboard.get("suites");
        assertThat(suites).hasSize(6);
        assertThat(dashboard.get("buildFingerprint")).isNotNull();
        assertThat(dashboard.get("securityPatch")).isEqualTo("2026-09-05");
    }
}
