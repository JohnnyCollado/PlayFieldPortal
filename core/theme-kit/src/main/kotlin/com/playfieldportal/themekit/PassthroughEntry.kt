package com.playfieldportal.themekit

import java.io.File
import java.io.OutputStream

/**
 * A zip entry this build does not understand, carried so a read-then-write loses nothing (A11).
 *
 * Streamed like [ThemeMotion], never held: it knows its [name] and how to copy itself somewhere
 * once, on demand. A passthrough entry is never extracted to disk by the launcher — it is only
 * re-written into a bundle — and its name has passed [PassthroughNames.isSafe] (plan 5.5).
 *
 * Equality is by name only, for the same reason as [ThemeMotion]: comparing content would mean
 * reading it.
 */
class PassthroughEntry internal constructor(
    /** Full zip entry name, e.g. `extras/thing.bin`. */
    val name: String,
    private val copy: (OutputStream) -> Long,
) {
    /** Streams the content to [out] and returns the byte count. Reads the source each time. */
    fun copyTo(out: OutputStream): Long = copy(out)

    override fun equals(other: Any?): Boolean = other is PassthroughEntry && name == other.name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = "PassthroughEntry($name)"

    companion object {
        /** Entry backed by bytes already in hand. For tests and small synthetic bundles. */
        fun ofBytes(name: String, bytes: ByteArray): PassthroughEntry =
            PassthroughEntry(name) { out -> out.write(bytes); bytes.size.toLong() }

        /** Entry streamed from a file on disk (the Studio's scratch copy), never held. */
        fun ofFile(name: String, file: File): PassthroughEntry =
            PassthroughEntry(name) { out -> file.inputStream().use { it.copyTo(out) } }
    }
}

/** The §5.5 name rule for passthrough entries. */
object PassthroughNames {
    private val SAFE = Regex("^[a-z0-9_]+(/[a-z0-9_]+)?\\.[a-z0-9]{1,5}$")
    private const val MAX_LENGTH = 96

    /**
     * `^[a-z0-9_]+(/[a-z0-9_]+)?\.[a-z0-9]{1,5}$`, at most 96 chars. The regex alone already
     * excludes `..`, a leading `/`, `\` and a second directory level; the colliding-with-a-
     * registered-entry check lives in [PfpThemeCodec] where the registries are.
     */
    fun isSafe(name: String): Boolean = name.length <= MAX_LENGTH && SAFE.matches(name)
}
