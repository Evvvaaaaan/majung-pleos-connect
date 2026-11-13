package io.majung.pleos.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.model.PassengerProfile
import org.json.JSONObject

data class ParsedGleoCommand(
    val functionName: String,
    val passengerName: String? = null,
    val passengerProfile: PassengerProfile? = null,
    val destinationKeyword: String? = null,
    val hasCarSeat: Boolean = false,
    val manualOffsetPreset: ManualOffsetPreset? = null,
    val lifecycleCommand: String? = null
)

object GleoProtocolCodec {
    const val EXTRA_ACTION_PARAM = "ACTION_PARAM"
    const val ACTION_GLEO_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    fun parseActionIntent(action: String?, rawActionParam: String?): Result<ParsedGleoCommand> = runCatching {
        require(!action.isNullOrBlank()) { "Missing Gleo intent action" }
        val normalizedJson = normalizeActionParamJson(rawActionParam)
        val json = JSONObject(normalizedJson)

        when (action) {
            "START_SAFE_PICKUP" -> {
                val profileRaw = json.optString("passengerProfile", "ADULT").uppercase()
                val profile = PassengerProfile.entries.firstOrNull { it.name == profileRaw }
                    ?: PassengerProfile.ADULT
                val destination = json.optString("destinationKeyword", "").trim()
                require(destination.isNotEmpty()) { "destinationKeyword is required for START_SAFE_PICKUP" }
                val passengerName = json.optString("passengerName", "").trim().ifEmpty { null }
                val hasCarSeat = json.optBoolean("hasCarSeat", false)
                ParsedGleoCommand(
                    functionName = action,
                    passengerName = passengerName,
                    passengerProfile = profile,
                    destinationKeyword = destination,
                    hasCarSeat = hasCarSeat
                )
            }
            "SHARE_PICKUP_OFFSET" -> {
                val presetRaw = json.optString("manualOffsetPreset", "AHEAD_12M").uppercase()
                val preset = ManualOffsetPreset.entries.firstOrNull { it.name == presetRaw }
                    ?: ManualOffsetPreset.AHEAD_12M
                ParsedGleoCommand(
                    functionName = action,
                    manualOffsetPreset = preset
                )
            }
            "COMPLETE_OR_CANCEL_PICKUP" -> {
                val cmd = json.optString("commandType", "BOARDING_CONFIRMED").uppercase()
                ParsedGleoCommand(
                    functionName = action,
                    lifecycleCommand = cmd
                )
            }
            else -> throw IllegalArgumentException("Unsupported Gleo function: $action")
        }
    }

    fun buildGleoCallbackPayload(
        functionName: String,
        status: String,
        message: String,
        dataJson: String? = null
    ): String = JSONObject().apply {
        put("functionName", functionName)
        put("status", status)
        put("message", message)
        if (dataJson != null) put("data", dataJson)
    }.toString()

    private fun normalizeActionParamJson(raw: String?): String {
        if (raw.isNullOrBlank()) return "{}"
        val trimmed = raw.trim()
        return if (trimmed.startsWith('"') && trimmed.endsWith('"') && trimmed.length >= 2) {
            trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
        } else {
            trimmed
        }
    }
}

object GleoCommandBus {
    var onServiceCommandReceived: ((Context, ParsedGleoCommand) -> Result<String>)? = null
}

class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val rawParam = intent.getStringExtra(GleoProtocolCodec.EXTRA_ACTION_PARAM)

        GleoProtocolCodec.parseActionIntent(action, rawParam)
            .onSuccess { command ->
                val execResult = GleoCommandBus.onServiceCommandReceived?.invoke(context, command)
                    ?: Result.success("Queued Gleo command ${command.functionName}")
                execResult.fold(
                    onSuccess = { summary ->
                        sendGleoBroadcast(context, command.functionName, "success", summary)
                    },
                    onFailure = { err ->
                        sendGleoBroadcast(context, command.functionName, "error", err.message ?: "Command failed")
                    }
                )
            }
            .onFailure { err ->
                Log.e(TAG, "Failed to parse Gleo ACTION_PARAM: $err")
                sendGleoBroadcast(context, action, "error", "Invalid parameters for $action")
            }
    }

    companion object {
        private const val TAG = "MajungGleoReceiver"

        fun sendGleoBroadcast(
            context: Context,
            functionName: String,
            status: String,
            message: String,
            data: String? = null
        ) {
            runCatching {
                val payload = GleoProtocolCodec.buildGleoCallbackPayload(functionName, status, message, data)
                Intent(GleoProtocolCodec.ACTION_GLEO_RESPONSE).apply {
                    putExtra("packageName", context.packageName)
                    putExtra("response", payload)
                }.also { context.sendBroadcast(it) }
            }
        }
    }
}
