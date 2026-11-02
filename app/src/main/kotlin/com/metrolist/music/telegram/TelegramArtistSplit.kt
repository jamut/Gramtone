/**
 * Gramtone (Metrolist fork) - Telegram source
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

/**
 * Turns an artist tag into separate artists.
 *
 *  - ";" always separates ("Kanye West; Playboi Carti").
 *  - "," "&" "and" "x" "feat." "with" "+" separate only when every piece is an artist the library already
 *    knows on its own, so "Frank Ocean & Steve Lacy" splits (both appear elsewhere) but band names like
 *    "Earth, Wind & Fire" and "Tyler, The Creator" stay whole.
 *  - To force it either way, use ";" in the tag, or edit the song in the app.
 */
object ArtistSplit {
    /** Names that contain a separator but are a single artist. Lowercase. */
    private val keepWhole = setOf(
        "earth, wind & fire", "tyler, the creator", "simon & garfunkel", "crosby, stills, nash & young",
        "crosby, stills & nash", "hall & oates", "sly & the family stone", "kool & the gang",
        "mumford & sons", "florence + the machine", "florence and the machine", "brooks & dunn",
        "years & years", "iron & wine", "belle & sebastian", "angus & julia stone", "chase & status",
        "echo & the bunnymen", "hootie & the blowfish", "guns n' roses", "me first and the gimme gimmes",
        "emerson, lake & palmer", "blood, sweat & tears", "peter, paul & mary",
        "tom petty and the heartbreakers", "bob marley & the wailers", "prince and the revolution",
        "huey lewis and the news", "matt and kim",
    )

    private val soft = Regex("""\s*(?:,|&|\+|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)

    @Volatile
    private var known: Set<String> = emptySet()

    private fun norm(s: String) = s.trim().lowercase()

    private fun pieces(part: String): List<String> =
        soft.split(part).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Learns which names exist as artists on their own. Call with every artist tag in the library.
     * A name is known when it appears alone on some song, or inside two or more different combined tags.
     */
    fun learn(rawTags: Collection<String>) {
        val solo = HashSet<String>()
        val inCombos = HashMap<String, MutableSet<String>>()
        for (raw in rawTags.toSet()) {
            for (part in raw.split(';', '\u0000')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val ps = pieces(p)
                if (ps.size < 2) solo.add(norm(p))
                else for (x in ps) inCombos.getOrPut(norm(x)) { HashSet() }.add(norm(p))
            }
        }
        val learned = HashSet<String>(solo)
        for ((name, combos) in inCombos) if (combos.size >= 2) learned.add(name)
        known = learned
    }

    fun split(raw: String): List<String> {
        val out = ArrayList<String>()
        for (part in raw.split(';', '\u0000')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (norm(p) in keepWhole) { out.add(p); continue }
            val ps = pieces(p)
            if (ps.size >= 2 && ps.all { norm(it) in known }) out.addAll(ps) else out.add(p)
        }
        return out.distinct()
    }
}
