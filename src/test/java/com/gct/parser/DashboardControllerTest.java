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
    void dashboardStartsEmptyUntilReportsAreAnalyzed() {
        var dashboard = controller.dashboard();

        assertThat(dashboard).containsKeys(
                "generatedAt",
                "buildFingerprint",
                "securityPatch",
                "overall",
                "suites",
                "incompleteModules",
                "failures"
        );

        assertThat((java.util.List<?>) dashboard.get("suites")).isEmpty();
        assertThat((java.util.List<?>) dashboard.get("incompleteModules")).isEmpty();
        assertThat((java.util.List<?>) dashboard.get("failures")).isEmpty();
    }
}
