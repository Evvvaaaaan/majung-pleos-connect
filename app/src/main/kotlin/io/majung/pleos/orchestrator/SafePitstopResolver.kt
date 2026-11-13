package io.majung.pleos.orchestrator

import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.model.RecipientChannel
import io.majung.pleos.model.SafePitstopStop
import io.majung.pleos.model.SeatZone
import org.json.JSONObject

class SafePitstopResolver(private val safePitstopsJsonText: String) {

    fun resolveKeywordToSafePitstop(
        keyword: String,
        passengerName: String = "지수",
        profile: PassengerProfile = PassengerProfile.ADULT,
        hasCarSeat: Boolean = false,
        channel: RecipientChannel = RecipientChannel.ZERO_INSTALL_WEB,
        targetSeat: SeatZone = SeatZone.ROW_1_RIGHT
    ): SafePitstopStop {
        val root = JSONObject(safePitstopsJsonText)
        val arr = root.getJSONArray("pitstops")
        val normalized = keyword.trim()

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val aliases = item.getJSONArray("keywordAliases")
            for (j in 0 until aliases.length()) {
                val alias = aliases.getString(j)
                if (normalized.contains(alias) || alias.contains(normalized)) {
                    val candsJson = item.optJSONArray("candidateSignboards")
                    val cands = buildList {
                        if (candsJson != null) {
                            for (k in 0 until candsJson.length()) add(candsJson.getString(k))
                        }
                        if (isEmpty()) add(item.optString("landmarkSignboard", item.getString("pitstopName")))
                    }
                    return SafePitstopStop(
                        stopId = item.getString("id"),
                        passengerName = passengerName.ifBlank { defaultLabelForProfile(profile) },
                        profile = profile,
                        targetSeat = targetSeat,
                        hasCarSeat = hasCarSeat,
                        channel = channel,
                        landmarkSignboard = item.optString("landmarkSignboard", cands.first()),
                        candidateSignboards = cands,
                        pitstopName = item.getString("pitstopName"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        poiId = item.getString("poiId"),
                        address = item.getString("address"),
                        rawPinWasInSchoolZone = item.getBoolean("rawPinInSchoolZone"),
                        walkingMetersWithoutCrossing = item.getInt("walkingMetersWithoutCrossing")
                    )
                }
            }
        }

        val fallbackSign = "${normalized} 정문 우측 간판"
        return SafePitstopStop(
            stopId = "ps_dynamic_${normalized.hashCode()}",
            passengerName = passengerName.ifBlank { defaultLabelForProfile(profile) },
            profile = profile,
            targetSeat = targetSeat,
            hasCarSeat = hasCarSeat,
            channel = channel,
            landmarkSignboard = fallbackSign,
            candidateSignboards = listOf(fallbackSign, "${normalized} 건너편 이면도로 포켓"),
            pitstopName = "${normalized} 인근 정차 후보 포켓",
            latitude = 37.498812,
            longitude = 127.061420,
            poiId = "pleos_poi_default",
            address = "${normalized} 우측 승하차 후보 구간",
            rawPinWasInSchoolZone = (profile == PassengerProfile.CHILD),
            walkingMetersWithoutCrossing = 45
        )
    }

    private fun defaultLabelForProfile(profile: PassengerProfile): String = when (profile) {
        PassengerProfile.CHILD -> "자녀"
        PassengerProfile.SENIOR -> "어르신"
        PassengerProfile.ADULT -> "지수"
    }
}
