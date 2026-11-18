package app.familygem.geni.sync

/**
 * Loads the relatives of a person breadth-first, up to a number of relationship steps.
 * With depth N the people within N steps end up cached: the families of people up to N-1 steps away are loaded.
 * @param loader Loads the immediate family of a profile
 * @param relatives Geni IDs of the known relatives of a profile
 * @param isFresh True if the profile's family was loaded recently and needs no load
 * @param mayLoad False stops the pass before the next load (e.g. the network became metered)
 */
class Prefetcher(
    private val loader: suspend (String) -> LoadResult,
    private val relatives: suspend (String) -> List<String>,
    private val isFresh: suspend (String) -> Boolean,
    private val mayLoad: suspend () -> Boolean = { true }
) {
    /** False if the last [run] stopped before visiting everyone (login needed, not a Geni tree, [mayLoad] false) or a load failed. */
    var completed = false
        private set

    /** @return Number of successful loads */
    suspend fun run(startGeniId: String, depth: Int): Int {
        completed = false
        var failed = false
        var loads = 0
        val visited = mutableSetOf(startGeniId)
        var level = listOf(startGeniId)
        repeat(depth) { step ->
            val next = mutableListOf<String>()
            for (requested in level) {
                var geniId = requested
                if (!isFresh(geniId)) {
                    if (!mayLoad()) return loads
                    when (val result = loader(geniId)) {
                        is LoadResult.Loaded -> {
                            loads++
                            // Merged on Geni: the relatives are now the survivor's
                            result.profileId?.takeIf { it != geniId }?.let { geniId = it; visited.add(it) }
                        }
                        LoadResult.NeedsLogin, LoadResult.NotAGeniTree -> return loads
                        is LoadResult.Failed -> failed = true // Skipped; its known relatives are still visited
                        is LoadResult.Gone -> Unit
                    }
                }
                if (step < depth - 1) relatives(geniId).filter { visited.add(it) }.forEach { next += it } // Last level: not needed
            }
            level = next
        }
        completed = !failed
        return loads
    }
}
