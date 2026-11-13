package io.majung.pleos.runtime

import android.content.Context
import io.majung.pleos.sdk.PleosRuntimeBridgeImpl

fun createMajungRuntimeBridge(context: Context): MajungRuntimeBridge =
    PleosRuntimeBridgeImpl(context)
