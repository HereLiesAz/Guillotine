package com.hereliesaz.guillotine.operation

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationServiceTypeTest {
    @Test
    fun mediaProcessingOnlyOn35Plus() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, OperationService.foregroundType(35))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, OperationService.foregroundType(37))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, OperationService.foregroundType(34))
        assertEquals(0, OperationService.foregroundType(33))
    }
}
