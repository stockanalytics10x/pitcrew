package com.example.washguard

import org.junit.Assert.*
import org.junit.Test

class DriveRepositoryTest {
    @Test fun finishRequiresVerifiedParking() {
        val repository = DemoDriveRepository()
        repository.finish(DriveAccess.UNKNOWN)
        assertFalse(repository.session.value.completed)
        repository.finish(DriveAccess.RESTRICTED)
        assertFalse(repository.session.value.completed)
        repository.finish(DriveAccess.PARKED)
        assertTrue(repository.session.value.completed)
    }
    @Test fun restartCannotDiscardReportWhileRestricted() {
        val repository = DemoDriveRepository()
        repository.finish(DriveAccess.PARKED)
        repository.restart(DriveAccess.RESTRICTED)
        assertTrue(repository.session.value.completed)
        repository.restart(DriveAccess.PARKED)
        assertFalse(repository.session.value.completed)
        assertTrue(repository.session.value.demo)
    }
    @Test fun unsupportedSignalsHaveNoFabricatedScores() {
        val session = DriveSession(demo = false)
        assertNull(session.similarity)
        assertNull(session.smoothness)
        assertNull(session.efficiency)
        assertTrue(session.flow.isEmpty())
    }
}
