package io.github.jgfyyvzss.gliderpilotlog.core.weglide

/**
 * Which logbook glider supplies the WeGlide aircraft for an upload. The glider of a flight saved from the file comes
 * first, since the pilot chose it on the form; then the glider matching the file's glider ID header. A glider with a
 * WeGlide type is preferred, so a header match with a type beats a saved glider without one; failing both, the
 * glider still names what to pick a type for.
 */
object UploadAircraft {
    fun <G> choose(saved: G?, matched: G?, aircraftId: (G) -> Long?): G? =
        saved?.takeIf { aircraftId(it) != null }
            ?: matched?.takeIf { aircraftId(it) != null }
            ?: saved
            ?: matched
}
