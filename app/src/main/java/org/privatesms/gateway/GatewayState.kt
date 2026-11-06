package org.privatesms.gateway

import kotlinx.coroutines.flow.MutableStateFlow

/** Live state observed by the UI. */
object GatewayState {
    val running = MutableStateFlow(false)
    val lastPoll = MutableStateFlow("Not polled yet")
    val sent = MutableStateFlow(0)
    val delivered = MutableStateFlow(0)
    val failed = MutableStateFlow(0)
}
