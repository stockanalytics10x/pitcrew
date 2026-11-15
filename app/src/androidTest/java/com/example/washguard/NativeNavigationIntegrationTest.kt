package com.example.washguard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in device verification; opens search screens but never changes a route or selects a place. */
@RunWith(AndroidJUnit4::class)
class NativeNavigationIntegrationTest {
    @Test fun oemSearchRequests() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val connected=CountDownLatch(1)
        val ready=CountDownLatch(1)
        lateinit var api:NaviHelper
        val listener=object:NaviHelperEventListener {
            override fun onRouteStateInfo(routeStateInfo:RouteStateInfo) {ready.countDown()}
        }
        instrumentation.runOnMainSync {
            api=NaviHelper(context.applicationContext)
            api.initialize(onConnected={api.addListener(listener);connected.countDown()},onFailed={})
        }
        try {
            assertTrue("OEM navigation service connection timed out",connected.await(20,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {api.requestNavigationScreen(NavigationScreenRequest.Foreground);api.getRouteStateInfo()}
            assertTrue("OEM navigation is not ready; initialize its map first",ready.await(20,TimeUnit.SECONDS))
            val kind=NativeSearchKind.valueOf(InstrumentationRegistry.getArguments().getString("nativeKind") ?: "FUEL")
            instrumentation.runOnMainSync {api.requestNavigationScreen(NavigationScreenRequest.Foreground);api.requestNavigationScreen(nativeSearchRequest(kind))}
            android.util.Log.i("PitstopNaviDeviceTest","OEM request sent: ${kind.name}")
            // Allow the OEM app to render for the host-side screenshot and request log check.
            Thread.sleep(2500)
        } finally {instrumentation.runOnMainSync {api.removeListener(listener);api.release()}}
    }
}
