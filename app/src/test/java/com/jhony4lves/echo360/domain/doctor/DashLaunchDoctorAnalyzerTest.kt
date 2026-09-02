package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashLaunchDoctorAnalyzerTest {
    private val analyzer = DashLaunchDoctorAnalyzer()

    @Test
    fun `clean plugin inventory produces no warning or error`() {
        val snapshot = snapshot(
            options = listOf(
                option(52, "Plugins", "plugin1", "Hdd:\\Plugins\\first.xex"),
                option(53, "Plugins", "plugin2", "Usb:\\Plugins\\second.XEX"),
                option(54, "Plugins", "plugin3", ""),
                option(55, "Plugins", "plugin4", "NULL"),
                option(56, "Plugins", "plugin5", ""),
                option(32, "Behavior", "exchandler", "true"),
            ),
        )

        val findings = analyzer.analyze(snapshot)

        assertFalse(findings.any { it.severity == IntegritySeverity.Warning || it.severity == IntegritySeverity.Error })
        assertEquals(2, snapshot.plugins.count { it.configured })
    }

    @Test
    fun `duplicate plugin path is warning with normalized slash and case`() {
        val snapshot = snapshot(
            options = listOf(
                option(52, "Plugins", "plugin1", "Hdd:\\Plugins\\Echo.xex"),
                option(53, "Plugins", "plugin2", "hdd:/plugins/echo.XEX"),
            ),
        )

        val duplicate = analyzer.analyze(snapshot).single {
            it.code == DashLaunchDoctorAnalyzer.CODE_PLUGIN_DUPLICATE
        }

        assertEquals(IntegritySeverity.Warning, duplicate.severity)
        assertTrue(duplicate.evidence.contains("plugin1"))
        assertTrue(duplicate.evidence.contains("plugin2"))
    }

    @Test
    fun `plugin traversal is error`() {
        val finding = analyzer.analyze(
            snapshot(listOf(option(52, "Plugins", "plugin1", "Hdd:\\Plugins\\..\\bad.xex"))),
        ).single { it.code == DashLaunchDoctorAnalyzer.CODE_PLUGIN_TRAVERSAL }

        assertEquals(IntegritySeverity.Error, finding.severity)
    }

    @Test
    fun `configured non xex plugin is warning`() {
        val finding = analyzer.analyze(
            snapshot(listOf(option(52, "Plugins", "plugin1", "Hdd:\\Plugins\\plugin.dll"))),
        ).single { it.code == DashLaunchDoctorAnalyzer.CODE_PLUGIN_NON_XEX }

        assertEquals(IntegritySeverity.Warning, finding.severity)
    }

    @Test
    fun `watched launch path traversal is error`() {
        val finding = analyzer.analyze(
            snapshot(listOf(option(57, "Paths", "Default", "Hdd:\\Aurora\\..\\bad.xex"))),
        ).single { it.code == DashLaunchDoctorAnalyzer.CODE_PATH_TRAVERSAL }

        assertEquals(IntegritySeverity.Error, finding.severity)
    }

    @Test
    fun `dumpfile with disabled exception handler is warning`() {
        val findings = analyzer.analyze(
            snapshot(
                listOf(
                    option(32, "Behavior", "exchandler", "FALSE"),
                    option(51, "Paths", "dumpfile", "Usb:\\crashlog.txt"),
                ),
            ),
        )

        val finding = findings.single {
            it.code == DashLaunchDoctorAnalyzer.CODE_DUMP_WITHOUT_EXCEPTION_HANDLER
        }
        assertEquals(IntegritySeverity.Warning, finding.severity)
        assertFalse(findings.any { it.code == DashLaunchDoctorAnalyzer.CODE_EXCEPTION_HANDLER_DISABLED })
    }

    @Test
    fun `disabled exception handler without dumpfile is informational`() {
        val finding = analyzer.analyze(
            snapshot(listOf(option(32, "Behavior", "exchandler", "false"))),
        ).single { it.code == DashLaunchDoctorAnalyzer.CODE_EXCEPTION_HANDLER_DISABLED }

        assertEquals(IntegritySeverity.Info, finding.severity)
    }

    @Test
    fun `duplicate option schema entry is warning`() {
        val finding = analyzer.analyze(
            snapshot(
                listOf(
                    option(15, "Net", "liveblock", "true"),
                    option(99, "net", "LIVEBLOCK", "false"),
                ),
            ),
        ).single { it.code == DashLaunchDoctorAnalyzer.CODE_DUPLICATE_OPTION }

        assertEquals(IntegritySeverity.Warning, finding.severity)
    }

    @Test
    fun `incomplete version stays informational`() {
        val finding = analyzer.analyze(
            DashLaunchSnapshot(emptyList(), DashLaunchVersion(kernel = 0, major = 0, minor = 0, build = 0)),
        ).single { it.code == DashLaunchDoctorAnalyzer.CODE_VERSION_INCOMPLETE }

        assertEquals(IntegritySeverity.Info, finding.severity)
    }

    private fun snapshot(options: List<DashLaunchOption>) = DashLaunchSnapshot(
        options = options,
        version = DashLaunchVersion(kernel = 17559, major = 3, minor = 21, build = 601),
    )

    private fun option(id: Long, category: String, name: String, value: String) = DashLaunchOption(
        id = id,
        category = category,
        name = name,
        value = value,
    )
}
