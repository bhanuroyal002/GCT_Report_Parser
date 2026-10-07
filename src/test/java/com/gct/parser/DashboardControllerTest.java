package com.gct.parser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
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

        assertThat((List<?>) dashboard.get("suites")).isEmpty();
        assertThat((List<?>) dashboard.get("incompleteModules")).isEmpty();
        assertThat((List<?>) dashboard.get("failures")).isEmpty();
    }

    @Test
    void parsesTradefedSummaryFromResultXml() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Result suite_name="GTS" suite_plan="gts" suite_version="14_r2"
                        suite_build_number="15985168" host_name="CHEND5VYV6Q3"
                        os_name="Linux" os_version="6.8.0-139-generic"
                        start_display="Fri Sep 25 10:34:38 IST 2026"
                        end_display="Fri Sep 25 10:36:53 IST 2026">
                  <Summary pass="1397" failed="13" warning="0" modules_done="150" modules_total="150"/>
                  <Build build_fingerprint="Proximus/uiw4068pxm/uiw4068pxm:12/STT5.251221.001/PXM-SW-1.8.1:user/release-keys"
                         build_version_security_patch="2026-08-01"
                         build_version_release="12" build_version_sdk="31"
                         build_abis="armeabi-v7a,armeabi"/>
                  <Module name="ExampleModule" abi="armeabi-v7a" done="true" pass="1">
                    <TestCase name="ExampleTest">
                      <Test name="testPass" result="pass"/>
                    </TestCase>
                  </Module>
                </Result>
                """;

        MockMultipartFile zip = new MockMultipartFile(
                "files", "GTS.zip", "application/zip", createZip(xml)
        );

        Map<String, Object> body = controller.analyze(new MockMultipartFile[]{zip}).getBody();
        assertThat(body).isNotNull();

        assertThat(body.get("buildFingerprint"))
                .isEqualTo("Proximus/uiw4068pxm/uiw4068pxm:12/STT5.251221.001/PXM-SW-1.8.1:user/release-keys");
        assertThat(body.get("securityPatch")).isEqualTo("2026-08-01");

        List<Map<String, Object>> suites = (List<Map<String, Object>>) body.get("suites");
        assertThat(suites).hasSize(1);

        Map<String, Object> gts = suites.get(0);
        assertThat(gts.get("name")).isEqualTo("GTS");
        assertThat(gts.get("plan")).isEqualTo("GTS");
        assertThat(gts.get("version")).isEqualTo("14_r2");
        assertThat(gts.get("buildNumber")).isEqualTo("15985168");
        assertThat(gts.get("passed")).isEqualTo(1397);
        assertThat(gts.get("failed")).isEqualTo(13);
        assertThat(gts.get("modules")).isEqualTo(150);
        assertThat(gts.get("completedModules")).isEqualTo(150);
        assertThat(gts.get("testCases")).isEqualTo(1410);
        assertThat(gts.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void aTestThatPassesInAnyReportIsCountedAsPassed() {
        String failedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Result suite_name="TVTS" suite_plan="tvts"
                        start_display="Wed Oct 07 10:00:00 IST 2026">
                  <Summary pass="0" failed="1" modules_done="1" modules_total="1"/>
                  <Build build_fingerprint="test/device:16/BUILD/123:user/release-keys"/>
                  <Module name="ExampleModule" abi="armeabi-v7a" done="true" pass="0">
                    <TestCase name="ExampleTest">
                      <Test name="testRerun" result="fail">
                        <Failure message="First execution failed"/>
                      </Test>
                    </TestCase>
                  </Module>
                </Result>
                """;

        String passedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Result suite_name="TVTS" suite_plan="tvts"
                        start_display="Wed Oct 07 10:01:00 IST 2026">
                  <Summary pass="1" failed="0" modules_done="1" modules_total="1"/>
                  <Build build_fingerprint="test/device:16/BUILD/123:user/release-keys"/>
                  <Module name="ExampleModule" abi="armeabi-v7a" done="true" pass="1">
                    <TestCase name="ExampleTest">
                      <Test name="testRerun" result="pass"/>
                    </TestCase>
                  </Module>
                </Result>
                """;

        MockMultipartFile zip = new MockMultipartFile(
                "files", "TVTS.zip", "application/zip", createZipWithTwoResults(failedXml, passedXml)
        );

        Map<String, Object> body = controller.analyze(new MockMultipartFile[]{zip}).getBody();
        assertThat(body).isNotNull();

        List<Map<String, Object>> suites = (List<Map<String, Object>>) body.get("suites");
        assertThat(suites).hasSize(1);
        Map<String, Object> tvts = suites.get(0);

        assertThat(tvts.get("passed")).isEqualTo(1);
        assertThat(tvts.get("failed")).isEqualTo(0);
        assertThat(tvts.get("testCases")).isEqualTo(1);
    }

    private static byte[] createZipWithTwoResults(String firstXml, String secondXml) {
        try (var output = new java.io.ByteArrayOutputStream();
             var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("results/first/test_result.xml"));
            zip.write(firstXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new java.util.zip.ZipEntry("results/second/test_result.xml"));
            zip.write(secondXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.finish();
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] createZip(String xml) {
        try (var output = new java.io.ByteArrayOutputStream();
             var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("results/test_result.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
