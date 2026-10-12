package com.eeck.server.core.config

import java.io.File

/**
 * Where configuration comes from. Everything reads through [Env] rather than
 * `System.getenv` directly, so `main` can add a `.env` file underneath the real
 * environment without any setting knowing the difference. Tests never call
 * `main`, so they keep seeing only the process environment.
 */
object Env {
    @Volatile
    var lookup: (String) -> String? = System::getenv

    operator fun get(name: String): String? = lookup(name)
}

/**
 * Reads `.env` files the way Docker Compose does, so one file configures both a
 * compose deployment and a server started from an IDE or `gradlew run` — neither
 * of which loads it by itself. Real environment variables win over the file, and
 * an empty one counts as unset (compose passes `EECK_ACCESS_KEY=` through when
 * the variable is not defined).
 *
 * Only a file literally named `.env` is read. `.env.example` is a template to
 * copy, and a secret typed into it would never reach the server.
 */
object DotEnv {
    /** Directories searched, nearest first: the working directory and the folders above it. */
    private const val SEARCH_DEPTH = 3

    class Loaded(val values: Map<String, String>, val files: List<File>)

    fun load(startDir: File = File("").absoluteFile): Loaded {
        val files = generateSequence(startDir) { it.parentFile }
            .take(SEARCH_DEPTH)
            .map { File(it, ".env") }
            .filter { it.isFile }
            .toList()
        val values = LinkedHashMap<String, String>()
        // Nearest file first, so it overrides a farther one for the same name.
        for (file in files) {
            for ((name, value) in parse(file.readText())) values.putIfAbsent(name, value)
        }
        return Loaded(values, files)
    }

    /** The real environment first, then [fileValues] for whatever it leaves unset or empty. */
    fun layered(real: (String) -> String?, fileValues: Map<String, String>): (String) -> String? = { name ->
        real(name)?.takeIf { it.isNotEmpty() } ?: fileValues[name]?.takeIf { it.isNotEmpty() }
    }

    fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (rawLine in text.removePrefix("﻿").lineSequence()) {
            val line = rawLine.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val name = line.substring(0, eq).trim()
            result[name] = unquote(line.substring(eq + 1).trim())
        }
        return result
    }

    private fun unquote(value: String): String {
        val quote = value.firstOrNull()
        if ((quote == '"' || quote == '\'') && value.length >= 2 && value.last() == quote) {
            return value.substring(1, value.length - 1)
        }
        // Unquoted: a trailing " # comment" is not part of the value.
        return value.substringBefore(" #").trimEnd()
    }
}
