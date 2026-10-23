package io.github.jgfyyvzss.gliderpilotlog.core.backup

/** What a merge would change, for the summary shown before committing. */
data class MergeSummary(val added: Int, val updated: Int, val unchanged: Int)

data class MergeResult(val merged: BackupFile, val summary: MergeSummary)

/**
 * Merge from Appendix B: records match by id and the newer `updated_at` wins, so a newer tombstone deletes and a
 * newer edit restores. Witness sign-offs match by id too, and a newer flight brings its own seal, which then
 * shows honestly whether it still matches. Opening balances match by role. Import batches are combined. Settings and the pilot
 * profile always stay as they are on this phone.
 */
object BackupMerge {
    fun merge(local: BackupFile, incoming: BackupFile): MergeResult {
        var added = 0
        var updated = 0
        var unchanged = 0

        fun <T> table(mine: List<T>, theirs: List<T>, key: (T) -> String, updatedAt: (T) -> String): List<T> {
            val result = LinkedHashMap<String, T>()
            mine.forEach { result[key(it)] = it }
            theirs.forEach { t ->
                val m = result[key(t)]
                when {
                    m == null -> { result[key(t)] = t; added++ }
                    newer(updatedAt(t), updatedAt(m)) -> { result[key(t)] = t; updated++ }
                    else -> unchanged++
                }
            }
            return result.values.toList()
        }

        val merged = local.copy(
            launchTypes = table(local.launchTypes, incoming.launchTypes, { it.id }, { it.updatedAt }),
            clubs = table(local.clubs, incoming.clubs, { it.id }, { it.updatedAt }),
            airfields = table(local.airfields, incoming.airfields, { it.id }, { it.updatedAt }),
            gliders = table(local.gliders, incoming.gliders, { it.id }, { it.updatedAt }),
            flights = table(local.flights, incoming.flights, { it.id }, { it.updatedAt }),
            witnessSignoffs = table(local.witnessSignoffs, incoming.witnessSignoffs, { it.id }, { it.updatedAt })
                .map { keepPhoto(it, local.witnessSignoffs + incoming.witnessSignoffs) },
            openingBalances = table(local.openingBalances, incoming.openingBalances, { it.role }, { it.updatedAt }),
            importBatches = (local.importBatches + incoming.importBatches.filter { b -> local.importBatches.none { it.id == b.id } }),
        )
        return MergeResult(merged, MergeSummary(added, updated, unchanged))
    }

    /**
     * A file exported without photos leaves the photo out without removing it. Whichever side wins, a sign-off
     * whose photo was not deliberately removed takes the photo from the other side when its hash is the same.
     */
    private fun keepPhoto(s: BackupWitnessSignoff, all: List<BackupWitnessSignoff>): BackupWitnessSignoff {
        if (s.photo != null || s.photoRemoved) return s
        val photo = all.firstOrNull { it.id == s.id && it.photo != null && it.photoSha256 == s.photoSha256 }?.photo
        return if (photo == null) s else s.copy(photo = photo)
    }

    /** True when [a] is strictly later than [b]. Unreadable times never win; validation reports them. */
    private fun newer(a: String, b: String): Boolean {
        val ta = BackupTime.fromIso(a) ?: return false
        val tb = BackupTime.fromIso(b) ?: return true
        return ta > tb
    }
}
