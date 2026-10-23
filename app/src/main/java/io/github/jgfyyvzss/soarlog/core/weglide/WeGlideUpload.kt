package io.github.jgfyyvzss.soarlog.core.weglide

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the pilot sends with an upload. The values are never logged, exported or shown in full; [toString] hides
 * them so they cannot leak through a log line or a crash report.
 */
class WeGlideCredentials(val userId: String, val dateOfBirth: LocalDate) {
    override fun toString() = "WeGlideCredentials(hidden)"
    override fun equals(other: Any?) = other is WeGlideCredentials && other.userId == userId && other.dateOfBirth == dateOfBirth
    override fun hashCode() = userId.hashCode() * 31 + dateOfBirth.hashCode()

    companion object {
        /** The WeGlide ID is digits only. */
        fun validUserId(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' }
    }
}

/** The flight WeGlide created, from the first element of the 201 response, as XCSoar reads it. */
data class UploadedFlight(
    val id: Long?,
    val scoringDate: String?,
    val registration: String?,
    val competitionId: String?,
    val pilotName: String?,
    val aircraftName: String?,
)

object WeGlideUploadResponse {
    /** Null when the body is not a JSON array whose first element is an object. */
    fun parse(text: String): UploadedFlight? {
        val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        val o = array.firstOrNull() as? JsonObject ?: return null
        return UploadedFlight(
            id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull(),
            scoringDate = o.text("scoring_date"),
            registration = o.text("registration"),
            competitionId = o.text("competition_id"),
            pilotName = (o["user"] as? JsonObject)?.text("name"),
            aircraftName = (o["aircraft"] as? JsonObject)?.text("name"),
        )
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
