package io.github.jqssun.airplay.service

import kotlinx.coroutines.flow.MutableStateFlow

object StageVisibility {
    val appVisible = MutableStateFlow(false)
}
