package com.scraper.worker.util

import com.scraper.worker.dto.ScrapeResult
import com.scraper.worker.service.DeviceProfile
import org.mockito.Mockito
import java.util.function.Consumer

object TestUtil {
    @Suppress("UNCHECKED_CAST")
    fun <T> anyKotlin(type: Class<T>): T {
        Mockito.any(type)
        return when {
            type == DeviceProfile::class.java -> DeviceProfile("", 0, 0) as T
            type == ScrapeResult::class.java -> ScrapeResult(0, 0, "") as T
            type == Consumer::class.java -> Consumer<Any> { } as T
            type.name.contains("Function") -> { { _: Any -> } as T }
            type.isInterface -> Mockito.mock(type)
            else -> null as T
        }
    }
}
