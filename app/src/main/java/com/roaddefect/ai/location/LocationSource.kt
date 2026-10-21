package com.roaddefect.ai.location

import kotlinx.coroutines.flow.StateFlow

/** Abstraction over the GPS provider so the inspection logic can be tested with a fake. */
interface LocationSource {
    val fix: StateFlow<LocationFix?>

    /** @return false when location permission is missing. */
    fun start(): Boolean
    fun stop()
}
