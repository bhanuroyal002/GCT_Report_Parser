package com.gct.parser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
                "buildFingerprint", "generic/tv_demo/atv:14/UD1A.240905.001/1234567:userdebug/test-keys",
                "securityPatch", "2026-09-05",
                "overall", Map.of(
                        "totalTests", 12480,
                        "passed", 12166,
                        "failed", 214,
                        "blocked", 100
                ),
                "suites", List.of(
                        Map.of("name", "CTS", "modules", 212, "completedModules", 210, "testCases", 6840, "passed", 6721, "failed", 119, "status", "INCOMPLETE"),
                        Map.of("name", "GTS", "modules", 98, "completedModules", 98, "testCases", 2430, "passed", 2392, "failed", 38, "status", "COMPLETED"),
                        Map.of("name", "TVTS", "modules", 74, "completedModules", 72, "testCases", 1670, "passed", 1622, "failed", 48, "status", "INCOMPLETE"),
                        Map.of("name", "STS", "modules", 63, "completedModules", 63, "testCases", 820, "passed", 812, "failed", 8, "status", "COMPLETED"),
                        Map.of("name", "VTS", "modules", 41, "completedModules", 41, "testCases", 720, "passed", 719, "failed", 1, "status", "COMPLETED"),
                        Map.of("name", "CTS-on-GSI", "modules", 15, "completedModules", 15, "testCases", 0, "passed", 0, "failed", 0, "status", "COMPLETED")
                ),
                "incompleteModules", List.of(
                        Map.of("suite", "CTS", "module", "CtsBackgroundRestrictionsTestCases", "reason", "Module incomplete"),
                        Map.of("suite", "CTS", "module", "CtsWindowManagerDeviceTests", "reason", "Module incomplete"),
                        Map.of("suite", "TVTS", "module", "EnergymodesTest", "reason", "Execution interrupted")
                ),
                "failures", List.of(
                        Map.of("suite", "CTS", "module", "CtsAudioTestCases", "testCase", "testAudioRoute", "details", "Audio route mismatch"),
                        Map.of("suite", "CTS", "module", "CtsBackgroundRestrictionsTestCases", "testCase", "testRestriction", "details", "Unexpected background restriction state"),
                        Map.of("suite", "TVTS", "module", "EnergymodesTest", "testCase", "testEnergyModesAPK", "details", "Energy mode validation failed"),
                        Map.of("suite", "GTS", "module", "GmsCoreTestCases", "testCase", "testAccountSync", "details", "Account sync state mismatch")
                )
        );
    }
}
