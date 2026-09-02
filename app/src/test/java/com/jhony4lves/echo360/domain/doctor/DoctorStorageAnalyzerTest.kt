package com.jhony4lves.echo360.domain.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorStorageAnalyzerTest {
    private val analyzer = DoctorStorageAnalyzer()

    @Test
    fun `normal mount listing has no health findings`() {
        val snapshot = DoctorStorageSnapshot(
            origin = DoctorStorageOrigin.AuroraFtpCompatibility,
            mounts = listOf(
                DoctorStorageMount(
                    canonicalRoot = "/Hdd1",
                    observedName = "Hdd1",
                    entries = listOf(
                        DoctorStorageEntry(
                            name = "Content",
                            canonicalPath = "/Hdd1/Content",
                            objectType = DoctorStorageObjectType.Directory,
                            sizeBytes = 0L,
                        ),
                    ),
                    limitReached = false,
                ),
            ),
            rootEntryCount = 4,
            rootLimitReached = false,
            checkedAtEpochMs = 1L,
        )

        assertTrue(analyzer.analyze(snapshot).isEmpty())
    }

    @Test
    fun `duplicate canonical roots are warned`() {
        val snapshot = snapshot(
            mounts = listOf(
                mount("/Hdd1", "Hdd1"),
                mount("/hdd1", "HDD1"),
            ),
        )

        val findings = analyzer.analyze(snapshot)
        assertTrue(findings.any { it.code == DoctorStorageAnalyzer.CODE_DUPLICATE_ROOT })
    }

    @Test
    fun `bounded directory is informational not corruption`() {
        val snapshot = snapshot(
            mounts = listOf(mount("/Hdd1", "Hdd1", limitReached = true)),
        )

        val finding = analyzer.analyze(snapshot).single {
            it.code == DoctorStorageAnalyzer.CODE_DIRECTORY_LIMIT_REACHED
        }
        assertEquals("Info", finding.severity.name)
    }

    @Test
    fun `negative size is invalid metadata not file corruption`() {
        val snapshot = snapshot(
            mounts = listOf(
                DoctorStorageMount(
                    canonicalRoot = "/Hdd1",
                    observedName = "Hdd1",
                    entries = listOf(
                        DoctorStorageEntry(
                            name = "game.xex",
                            canonicalPath = "/Hdd1/game.xex",
                            objectType = DoctorStorageObjectType.File,
                            sizeBytes = -1L,
                        ),
                    ),
                    limitReached = false,
                ),
            ),
        )

        val finding = analyzer.analyze(snapshot).single {
            it.code == DoctorStorageAnalyzer.CODE_NEGATIVE_SIZE
        }
        assertTrue(finding.evidence.contains("-1"))
        assertTrue(finding.suggestedAction.contains("não prova corrupção"))
    }

    @Test
    fun `transport unavailable does not become no mounts health finding`() {
        val snapshot = DoctorStorageSnapshot(
            origin = DoctorStorageOrigin.AuroraFtpCompatibility,
            mounts = emptyList(),
            rootEntryCount = 0,
            rootLimitReached = false,
            unavailableDetail = "timeout",
            checkedAtEpochMs = 1L,
        )

        assertTrue(analyzer.analyze(snapshot).isEmpty())
    }

    @Test
    fun `responding source with no mounts is informational`() {
        val snapshot = snapshot(mounts = emptyList())
        val finding = analyzer.analyze(snapshot).single()
        assertEquals(DoctorStorageAnalyzer.CODE_NO_MOUNTS_VISIBLE, finding.code)
        assertEquals("Info", finding.severity.name)
    }

    private fun snapshot(mounts: List<DoctorStorageMount>) = DoctorStorageSnapshot(
        origin = DoctorStorageOrigin.AuroraFtpCompatibility,
        mounts = mounts,
        rootEntryCount = mounts.size,
        rootLimitReached = false,
        checkedAtEpochMs = 1L,
    )

    private fun mount(
        root: String,
        observedName: String,
        limitReached: Boolean = false,
    ) = DoctorStorageMount(
        canonicalRoot = root,
        observedName = observedName,
        entries = emptyList(),
        limitReached = limitReached,
    )
}
