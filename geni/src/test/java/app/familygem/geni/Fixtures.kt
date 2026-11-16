package app.familygem.geni

/** Geni sandbox responses saved in src/test/resources/geni. */
object Fixtures {
    fun read(name: String): String =
        Fixtures::class.java.getResourceAsStream("/geni/$name")!!.bufferedReader().use { it.readText() }
}
