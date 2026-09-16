package com.eeck.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Executable version of the vertical-slice import rules. Without this the
 * boundaries are a convention in a doc, and conventions rot silently — a
 * single stray `import` is all it takes to turn two independent slices into
 * one tangled package, and nothing would fail.
 *
 * The rules, in the order they matter:
 *  1. `core` knows nothing about features or wiring.
 *  2. A feature reaches another feature only through its `port` package — an
 *     interface declared by the *consumer* and implemented by the *provider*
 *     (dependency inversion), never by importing its service/store/resource.
 *  3. `app` is the composition root; nothing below it may depend on it.
 */
class ArchitectureTest {

    private data class SourceFile(
        val path: String,
        val layer: String,
        val feature: String?,
        val imports: List<String>,
    )

    private val sourceRoot: File = sequenceOf(
        File("src/main/kotlin/com/eeck/server"),
        File("server/src/main/kotlin/com/eeck/server"),
    ).firstOrNull { it.isDirectory }
        ?: error("server source root not found from ${File("").absolutePath}")

    private val sources: List<SourceFile> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file ->
            val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
            val segments = relative.split('/')
            SourceFile(
                path = relative,
                layer = segments.first(),
                feature = segments.getOrNull(1).takeIf { segments.first() == "features" },
                imports = file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("$PACKAGE_PREFIX_IMPORT") }
                    .map { it.removePrefix("import ").substringBefore(" as ") },
            )
        }
        .toList()

    /**
     * Guards the other three tests against passing vacuously: if the source
     * root ever moves, an empty file list would make every rule below "hold".
     */
    @Test
    fun `the source tree was actually scanned`() {
        assertTrue(sources.isNotEmpty(), "no Kotlin sources found under ${sourceRoot.absolutePath}")
        val layers = sources.map { it.layer }.toSet()
        assertTrue(
            layers.containsAll(setOf("core", "features", "app")),
            "expected core/features/app layers, found $layers",
        )
    }

    @Test
    fun `core never depends on features or app`() {
        val violations = sources
            .filter { it.layer == "core" }
            .flatMap { file ->
                file.imports
                    .filter { it.startsWith("$FEATURES_PREFIX") || it.startsWith("$APP_PREFIX") }
                    .map { "${file.path} imports $it" }
            }
        violations.assertNone("core must stay ignorant of features and wiring")
    }

    @Test
    fun `features depend on each other only through a declared port`() {
        val violations = sources
            .filter { it.feature != null }
            .flatMap { file ->
                file.imports
                    .filter { it.startsWith(FEATURES_PREFIX) }
                    .filterNot { it.startsWith("$FEATURES_PREFIX${file.feature}.") }
                    .filterNot { OTHER_FEATURE_PORT.matches(it) }
                    .map { "${file.path} imports $it" }
            }
        violations.assertNone(
            "a feature may only reach another feature through its `port` package " +
                "(interface declared by the consumer, implemented by the provider)",
        )
    }

    @Test
    fun `only app wires features together`() {
        val violations = sources
            .filterNot { it.layer == "app" }
            .flatMap { file ->
                file.imports
                    .filter { it.startsWith(APP_PREFIX) }
                    .map { "${file.path} imports $it" }
            }
        violations.assertNone("app is the composition root - nothing below it may depend on it")
    }

    private fun List<String>.assertNone(rule: String) {
        assertTrue(isEmpty(), joinToString(prefix = "$rule\n", separator = "\n") { "  - $it" })
    }

    private companion object {
        const val PACKAGE_PREFIX_IMPORT = "import com.eeck.server."
        const val FEATURES_PREFIX = "com.eeck.server.features."
        const val APP_PREFIX = "com.eeck.server.app."
        val OTHER_FEATURE_PORT = Regex("""^com\.eeck\.server\.features\.\w+\.port\.\w+$""")
    }
}
