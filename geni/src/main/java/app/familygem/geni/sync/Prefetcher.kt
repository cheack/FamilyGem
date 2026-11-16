package app.familygem.geni.sync

/**
 * Loads the relatives of a person breadth-first, up to a number of relationship steps.
 * With depth N the people within N steps end up cached: the families of people up to N-1 steps away are loaded.
 * @param loader Loads the immediate family of a profile
 * @param relatives Geni IDs of the known relatives of a profile
 * @param isFresh True if the profile's family was loaded recently and needs no load
 */
class Prefetcher(
    private val loader: suspend (String) -> LoadResult,
    private val relatives: suspend (String) -> List<String>,
    private val isFresh: suspend (String) -> Boolean
) {
    /** @return Number of successful loads */
    suspend fun run(startGeniId: String, depth: Int): Int {
        var loads = 0
        val visited = mutableSetOf(startGeniId)
        var level = listOf(startGeniId)
        repeat(depth) {
            val next = mutableListOf<String>()
            for (geniId in level) {
                if (!isFresh(geniId)) {
                    when (loader(geniId)) {
                        is LoadResult.Loaded -> loads++
                        LoadResult.NeedsLogin, LoadResult.NotAGeniTree -> return loads
                        is LoadResult.Failed -> Unit // Skipped; its known relatives are still visited
                    }
                }
                relatives(geniId).filter { visited.add(it) }.forEach { next += it }
            }
            level = next
        }
        return loads
    }
}
