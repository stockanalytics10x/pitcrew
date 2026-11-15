package com.example.washguard

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Reads emulator VHAL, not a DemoDriveRepository or injected application sample. */
class SpeedTelemetryIntegrationTest {
    @Test fun actualVehicleSpeedCallbacksReachAnalyzer() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,SpeedTelemetryClient.PERMISSION)
        lateinit var repository:LiveDriveRepository
        lateinit var client:SpeedTelemetryClient
        instrumentation.runOnMainSync {
            repository=LiveDriveRepository(context,"speed_flow_integration_test")
            repository.begin();client=SpeedTelemetryClient(context,repository);client.start()
        }
        try {
            val deadline=System.currentTimeMillis()+20_000
            while(System.currentTimeMillis()<deadline && (repository.session.value.telemetry?.samples?:0)<3) Thread.sleep(200)
            val telemetry=repository.session.value.telemetry!!
            assertTrue("VHAL speed did not arrive: $telemetry",telemetry.samples>=3)
            assertNotNull(telemetry.speedKmh)
            assertTrue(telemetry.observedSeconds>0)
            assertNotNull(telemetry.acceleration)
            instrumentation.runOnMainSync {repository.finish(DriveAccess.PARKED)}
            assertTrue(repository.session.value.completed)
            assertFalse(repository.session.value.telemetry!!.recording)
            val restored=LiveDriveRepository(context,"speed_flow_integration_test").session.value
            assertTrue(restored.completed)
            assertEquals(telemetry.samples,restored.telemetry!!.samples)
        } finally {
            instrumentation.runOnMainSync {client.close()}
            context.getSharedPreferences("speed_flow_integration_test",0).edit().clear().commit()
        }
    }
}
