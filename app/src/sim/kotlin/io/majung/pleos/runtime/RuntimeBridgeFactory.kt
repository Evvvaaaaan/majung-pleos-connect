package io.majung.pleos.runtime

import android.content.Context
import io.majung.pleos.sdk.SimRuntimeBridgeImpl

fun createMajungRuntimeBridge(context: Context): MajungRuntimeBridge =
    SimRuntimeBridgeImpl(context)
