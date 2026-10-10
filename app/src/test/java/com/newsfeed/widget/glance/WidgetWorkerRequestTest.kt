package com.newsfeed.widget.glance

import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-034: the periodic job (and every one-time refresh) must require a connected network, and the
 * interval must come from the caller, never a hard-coded 15 minutes. Built in one place
 * (WidgetWorker.periodicRequest / oneTimeRequest), so these tests cover every call site.
 */
class WidgetWorkerRequestTest {

    @Test
    fun `periodic request requires a connected network`() {
        val spec = WidgetWorker.periodicRequest(15).workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `periodic request keeps the interval it is given`() {
        assertEquals(TimeUnit.MINUTES.toMillis(60), WidgetWorker.periodicRequest(60).workSpec.intervalDuration)
        assertEquals(TimeUnit.MINUTES.toMillis(720), WidgetWorker.periodicRequest(720).workSpec.intervalDuration)
    }

    @Test
    fun `periodic request never goes below the WorkManager minimum`() {
        assertEquals(TimeUnit.MINUTES.toMillis(15), WidgetWorker.periodicRequest(1).workSpec.intervalDuration)
        assertEquals(TimeUnit.MINUTES.toMillis(15), WidgetWorker.periodicRequest(0).workSpec.intervalDuration)
    }

    @Test
    fun `one time refresh requires a connected network and is not periodic`() {
        val spec = WidgetWorker.oneTimeRequest().workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(!spec.isPeriodic)
    }
}
