package com.eeck.server.core.config

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotEnvTest {

    @Test
    fun `parses names, quotes, comments and export`() {
        val parsed = DotEnv.parse(
            """
            # a comment
            EECK_ACCESS_KEY=SECRET
            export EECK_PORT=4000
            EECK_HOST = "127.0.0.1"
            EECK_CORS_HOST='a.example, b.example'
            WITH_COMMENT=value # trailing note
            EMPTY=
            not a setting
            """.trimIndent(),
        )
        assertEquals("SECRET", parsed["EECK_ACCESS_KEY"])
        assertEquals("4000", parsed["EECK_PORT"])
        assertEquals("127.0.0.1", parsed["EECK_HOST"])
        assertEquals("a.example, b.example", parsed["EECK_CORS_HOST"])
        assertEquals("value", parsed["WITH_COMMENT"])
        assertEquals("", parsed["EMPTY"])
        assertNull(parsed["not a setting"])
    }

    @Test
    fun `a key with non-ascii characters survives`() {
        assertEquals("парольXYZ", DotEnv.parse("EECK_ACCESS_KEY=парольXYZ")["EECK_ACCESS_KEY"])
    }

    @Test
    fun `a byte order mark does not hide the first setting`() {
        assertEquals("1", DotEnv.parse("﻿EECK_PORT=1")["EECK_PORT"])
    }

    @Test
    fun `the real environment wins, and an empty one counts as unset`() {
        val lookup = DotEnv.layered(
            real = { mapOf("A" to "from-env", "B" to "")[it] },
            fileValues = mapOf("A" to "from-file", "B" to "from-file", "C" to "from-file"),
        )
        assertEquals("from-env", lookup("A"))
        assertEquals("from-file", lookup("B"))
        assertEquals("from-file", lookup("C"))
        assertNull(lookup("D"))
    }

    @Test
    fun `finds a dot-env in the working directory and the folders above it, nearest first`() {
        val root = Files.createTempDirectory("eeck-dotenv").toFile()
        try {
            val server = File(root, "server").apply { mkdirs() }
            File(root, ".env").writeText("EECK_ACCESS_KEY=outer\nEECK_PORT=1111\n")
            File(server, ".env").writeText("EECK_ACCESS_KEY=inner\n")

            val loaded = DotEnv.load(server)
            assertEquals("inner", loaded.values["EECK_ACCESS_KEY"])
            assertEquals("1111", loaded.values["EECK_PORT"])
            assertEquals(2, loaded.files.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `never reads the example template`() {
        val dir = Files.createTempDirectory("eeck-dotenv-example").toFile()
        try {
            File(dir, ".env.example").writeText("EECK_ACCESS_KEY=typed-in-the-wrong-file\n")
            val loaded = DotEnv.load(dir)
            assertTrue(loaded.files.none { it.name == ".env.example" })
            assertNull(loaded.values["EECK_ACCESS_KEY"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `config reads the access key through Env`() {
        val before = Env.lookup
        try {
            Env.lookup = { if (it == "EECK_ACCESS_KEY") "one, two" else null }
            assertEquals(listOf("one", "two"), ServerConfig().accessKeys)
        } finally {
            Env.lookup = before
        }
    }
}
