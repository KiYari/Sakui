package com.eeck.server.features.auth

import com.eeck.server.features.auth.model.AccessMode
import com.eeck.server.features.auth.service.AuthService
import com.eeck.server.features.auth.service.AuthSettings
import com.eeck.server.features.auth.service.ChangePasswordResult
import com.eeck.server.features.auth.service.CreateCodeResult
import com.eeck.server.features.auth.service.LoginMethod
import com.eeck.server.features.auth.service.LoginResult
import com.eeck.server.features.auth.service.PasswordSource
import com.eeck.server.features.auth.store.AuthFileStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {

    private var now = 1_000_000L
    private val dirs = mutableListOf<File>()

    private fun dir(): File = Files.createTempDirectory("eeck-auth").toFile().also(dirs::add)

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun service(
        passwords: List<String> = listOf("server-password"),
        master: String? = "master-key-for-tests",
        dir: File? = null,
        settings: AuthSettings = AuthSettings(),
    ) = AuthService(passwords, master, AuthFileStore(dir?.let { File(it, "auth.json") }), { now }, settings)

    private fun AuthService.create(typed: String? = null, ttl: Long = 900, label: String = "") =
        createCode(typed, ttl, label)

    private fun AuthService.newCode(ttl: Long = 900): String =
        (create(null, ttl) as CreateCodeResult.Created).code

    // ---- the server password ----------------------------------------------------------------

    @Test
    fun `an open server needs nothing`() {
        val auth = service(passwords = emptyList())
        assertFalse(auth.required)
        assertEquals(PasswordSource.NONE, auth.status().passwordSource)
    }

    @Test
    fun `the password signs in and the token it returns is accepted at the gate`() {
        val auth = service()
        val ok = auth.login("server-password") as LoginResult.Success
        assertEquals(LoginMethod.PASSWORD, ok.method)
        assertTrue(auth.accepts(ok.token))
        assertEquals(LoginResult.Rejected, auth.login("nope"))
        assertEquals(LoginResult.Rejected, auth.login(""))
        assertEquals(LoginResult.Rejected, auth.login("   "))
    }

    @Test
    fun `the password itself still works directly, for scripts`() {
        val auth = service()
        assertTrue(auth.accepts("server-password"))
        assertFalse(auth.accepts("server-passwor"))
    }

    @Test
    fun `a token the server never issued is refused`() {
        assertFalse(service().accepts("made-up-token"))
    }

    @Test
    fun `sessions expire when idle and when they get too old`() {
        val auth = service(settings = AuthSettings(userIdleMillis = 1_000, userMaxMillis = 2_500))
        val token = (auth.login("server-password") as LoginResult.Success).token

        now += 900
        assertTrue(auth.accepts(token), "use extends the session")
        now += 900
        assertTrue(auth.accepts(token))
        now += 900 // 2_700 after creation: past the hard cap even though it was just used
        assertFalse(auth.accepts(token), "never lives past its maximum age")

        val idle = (auth.login("server-password") as LoginResult.Success).token
        now += 1_100
        assertFalse(auth.accepts(idle), "idle sessions lapse")
    }

    @Test
    fun `logging out ends only that session`() {
        val auth = service()
        val a = (auth.login("server-password") as LoginResult.Success).token
        val b = (auth.login("server-password") as LoginResult.Success).token
        auth.logout(a)
        assertFalse(auth.accepts(a))
        assertTrue(auth.accepts(b))
    }

    // ---- who is let in --------------------------------------------------------------------------

    @Test
    fun `by default the server follows the password, as it always did`() {
        assertEquals(AccessMode.PROTECTED, service(passwords = listOf("pw")).accessMode)
        assertEquals(AccessMode.OPEN, service(passwords = emptyList()).accessMode)
    }

    @Test
    fun `all accepted switches the password off without forgetting it`() {
        val auth = service()
        auth.setAccessMode(AccessMode.OPEN)
        assertFalse(auth.required)
        assertEquals(AccessMode.OPEN, auth.status().accessMode)
        assertTrue(auth.status().hasPassword, "the password is kept")

        auth.setAccessMode(AccessMode.PROTECTED)
        assertTrue(auth.required)
        assertTrue(auth.login("server-password") is LoginResult.Success, "and works again")
    }

    @Test
    fun `protected with no password lets in only one-time codes`() {
        val auth = service(passwords = emptyList())
        auth.setAccessMode(AccessMode.PROTECTED)
        assertTrue(auth.required)
        assertFalse(auth.status().hasPassword)

        assertEquals(LoginResult.Rejected, auth.login("anything-at-all"))
        val code = auth.newCode()
        assertTrue(auth.login(code) is LoginResult.Success)
    }

    @Test
    fun `an explicit choice beats the password in the environment, and survives a restart`() {
        val dir = dir()
        service(dir = dir).setAccessMode(AccessMode.OPEN)

        val restarted = service(dir = dir) // the same env password is still configured
        assertFalse(restarted.required)
        assertEquals(AccessMode.OPEN, restarted.accessMode)

        restarted.setAccessMode(AccessMode.PROTECTED)
        assertTrue(service(dir = dir).required)
    }

    @Test
    fun `unspent codes and sessions survive a switch to open and back`() {
        val auth = service()
        val code = auth.newCode()
        val token = (auth.login("server-password") as LoginResult.Success).token

        auth.setAccessMode(AccessMode.OPEN)
        auth.setAccessMode(AccessMode.PROTECTED)

        assertTrue(auth.accepts(token))
        assertTrue(auth.login(code) is LoginResult.Success)
    }

    // ---- one-time codes ---------------------------------------------------------------------

    @Test
    fun `a generated code works exactly once`() {
        val auth = service()
        val code = auth.newCode()
        val first = auth.login(code) as LoginResult.Success
        assertEquals(LoginMethod.ONE_TIME_CODE, first.method)
        assertTrue(auth.accepts(first.token))
        assertEquals(LoginResult.Rejected, auth.login(code), "spent")
    }

    @Test
    fun `a generated code tolerates case, dashes and spaces`() {
        val auth = service()
        val code = auth.newCode()
        assertEquals(10 + 1, code.length, "ten characters in two groups")
        val sloppy = code.lowercase().replace("-", " ")
        assertTrue(auth.login(" $sloppy ") is LoginResult.Success)
    }

    @Test
    fun `a code expires`() {
        val auth = service()
        val code = auth.newCode(ttl = 120)
        now += 119_000
        assertEquals(1, auth.codes.active().size)
        now += 2_000
        assertEquals(LoginResult.Rejected, auth.login(code))
        assertTrue(auth.codes.active().isEmpty())
    }

    @Test
    fun `a wrong guess does not burn a real code`() {
        val auth = service()
        val code = auth.newCode()
        repeat(5) { assertEquals(LoginResult.Rejected, auth.login("WRONG-GUESS-$it")) }
        assertTrue(auth.login(code) is LoginResult.Success)
    }

    @Test
    fun `an admin can type a code, and it must match exactly`() {
        val auth = service()
        val created = auth.create("Pelmeni-2026") as CreateCodeResult.Created
        assertTrue(created.info.custom)
        assertEquals("Pelmeni-2026", created.code)
        assertEquals(LoginResult.Rejected, auth.login("pelmeni-2026"), "typed codes are case sensitive")
        assertTrue(auth.login("Pelmeni-2026") is LoginResult.Success)
        assertEquals(LoginResult.Rejected, auth.login("Pelmeni-2026"))
    }

    @Test
    fun `a typed code can be non latin`() {
        val auth = service()
        auth.create("сладкий-пирог-77")
        assertTrue(auth.login("сладкий-пирог-77") is LoginResult.Success)
    }

    @Test
    fun `weak or clashing typed codes are refused with a reason`() {
        val auth = service()
        for (weak in listOf("short", "aaaaaaaaaa", "12345678", "abcdefgh", "password", "  padded-code ")) {
            val result = auth.create(weak)
            assertTrue(result is CreateCodeResult.Invalid, "$weak should be refused")
        }
        assertTrue(auth.create("server-password") is CreateCodeResult.Invalid, "must differ from the password")
        auth.create("Unique-Code-1")
        assertTrue(auth.create("Unique-Code-1") is CreateCodeResult.Invalid, "no two identical active codes")
        assertEquals(1, auth.codes.active().size)
    }

    @Test
    fun `a code lifetime outside the allowed range is refused`() {
        val auth = service()
        val year = 365 * 24 * 3600L
        assertTrue(auth.create(null, ttl = 30) is CreateCodeResult.Invalid)
        assertTrue(auth.create(null, ttl = year + 1) is CreateCodeResult.Invalid)
        assertTrue(auth.create(null, ttl = 60) is CreateCodeResult.Created)
        assertTrue(auth.create(null, ttl = 24 * 3600) is CreateCodeResult.Created)
        for (days in listOf(7, 30, 90, 365)) {
            assertTrue(auth.create(null, ttl = days * 24 * 3600L) is CreateCodeResult.Created, "$days days")
        }
    }

    @Test
    fun `revoking a code kills it`() {
        val auth = service()
        val created = auth.create(null, 900, "for Vasya") as CreateCodeResult.Created
        assertEquals("for Vasya", auth.codes.active().single().label)
        assertTrue(auth.codes.revoke(created.info.id))
        assertFalse(auth.codes.revoke(created.info.id))
        assertEquals(LoginResult.Rejected, auth.login(created.code))
    }

    @Test
    fun `of many simultaneous attempts exactly one spends the code`() {
        val auth = service()
        val code = auth.newCode()
        val threads = 32
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        repeat(threads) {
            pool.submit {
                start.await()
                if (auth.login(code) is LoginResult.Success) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }

    // ---- changing the password --------------------------------------------------------------

    @Test
    fun `changing the password replaces the old one`() {
        val auth = service()
        assertEquals(ChangePasswordResult.Changed(0), auth.changePassword("A-brand-new-pass", signOutEveryone = false))
        assertEquals(LoginResult.Rejected, auth.login("server-password"))
        assertTrue(auth.login("A-brand-new-pass") is LoginResult.Success)
        assertFalse(auth.accepts("server-password"))
        assertTrue(auth.accepts("A-brand-new-pass"))
        assertEquals(PasswordSource.ADMIN_PANEL, auth.status().passwordSource)
    }

    @Test
    fun `a new password can sign everyone out, or leave them`() {
        val auth = service()
        val a = (auth.login("server-password") as LoginResult.Success).token
        val b = (auth.login("server-password") as LoginResult.Success).token

        assertEquals(ChangePasswordResult.Changed(0), auth.changePassword("Second-password-1", signOutEveryone = false))
        assertTrue(auth.accepts(a) && auth.accepts(b))

        assertEquals(ChangePasswordResult.Changed(2), auth.changePassword("Third-password-2", signOutEveryone = true))
        assertFalse(auth.accepts(a) || auth.accepts(b))
    }

    @Test
    fun `a weak new password is refused and nothing changes`() {
        val auth = service()
        assertTrue(auth.changePassword("short", false) is ChangePasswordResult.Invalid)
        assertTrue(auth.changePassword("password", false) is ChangePasswordResult.Invalid)
        assertTrue(auth.login("server-password") is LoginResult.Success)
    }

    @Test
    fun `setting a password closes an open server`() {
        val auth = service(passwords = emptyList())
        assertFalse(auth.required)
        auth.changePassword("Now-it-is-closed", false)
        assertTrue(auth.required)
        assertTrue(auth.login("Now-it-is-closed") is LoginResult.Success)
    }

    @Test
    fun `the changed password survives a restart and beats the environment`() {
        val dir = dir()
        service(dir = dir).changePassword("Persisted-pass-9", false)

        val restarted = service(dir = dir) // same env password as before: "server-password"
        assertTrue(restarted.login("Persisted-pass-9") is LoginResult.Success)
        assertEquals(LoginResult.Rejected, restarted.login("server-password"))
        assertEquals(PasswordSource.ADMIN_PANEL, restarted.status().passwordSource)
    }

    @Test
    fun `the panel can show the password it was given, and the one from the environment`() {
        assertEquals("server-password", service().revealPassword(), "from the environment")
        assertEquals("a, b", service(passwords = listOf("a", "b")).revealPassword(), "several keys are all shown")
        assertNull(service(passwords = emptyList()).revealPassword(), "nothing to show")

        val auth = service()
        auth.changePassword("Shown-in-the-panel-1", false)
        assertEquals("Shown-in-the-panel-1", auth.revealPassword(), "the one set in the panel wins")
        assertTrue(auth.status().passwordViewable)
    }

    @Test
    fun `a password set in the panel is kept readable so the panel can show it`() {
        val dir = dir()
        service(dir = dir).changePassword("Kept-as-text-123", false)

        assertTrue("Kept-as-text-123" in File(dir, "auth.json").readText())
        assertEquals("Kept-as-text-123", service(dir = dir).revealPassword(), "and it survives a restart")
    }

    @Test
    fun `a password stored by an older version as a hash still signs in, cannot be shown, and is replaced by a new one`() {
        val dir = dir()
        AuthFileStore(File(dir, "auth.json")).save(
            com.eeck.server.features.auth.store.AuthState(password = com.eeck.server.features.auth.model.PasswordHasher.hash("legacy-password-1")),
        )

        val auth = service(dir = dir)
        assertTrue(auth.required)
        assertTrue(auth.login("legacy-password-1") is LoginResult.Success)
        assertEquals(LoginResult.Rejected, auth.login("server-password"), "it replaces the environment one, as before")
        assertNull(auth.revealPassword(), "a hash cannot be turned back")
        assertFalse(auth.status().passwordViewable)

        auth.changePassword("Replacement-pass-2", false)
        assertEquals("Replacement-pass-2", auth.revealPassword())
        assertTrue(auth.status().passwordViewable)
        assertEquals(LoginResult.Rejected, auth.login("legacy-password-1"))
        assertTrue(auth.login("Replacement-pass-2") is LoginResult.Success)
    }

    @Test
    fun `a damaged auth file stops the server instead of being read as empty`() {
        val dir = dir()
        File(dir, "auth.json").writeText("{ this is not json")
        val failure = assertFailsWith<IllegalStateException> { service(dir = dir) }
        assertTrue("auth.json" in failure.message.orEmpty())
    }

    // ---- the master key ---------------------------------------------------------------------

    @Test
    fun `a configured master key opens the panel and nothing else does`() {
        val auth = service(master = "my-master-key-123")
        assertNotNull(auth.adminLogin("my-master-key-123"))
        assertNull(auth.adminLogin("server-password"), "the server password is not the master key")
        assertNull(auth.adminLogin("wrong"))
        assertNull(auth.adminLogin(""))
        assertNull(auth.autoGeneratedMasterKey, "nothing is generated when one is configured")
    }

    @Test
    fun `without a configured master key one is generated for this run, in memory only`() {
        val dir = dir()
        val auth = service(dir = dir, master = null)
        val key = assertNotNull(auth.autoGeneratedMasterKey)
        assertEquals(32, key.length)
        assertNotNull(auth.adminLogin(key))
        assertNull(auth.adminLogin("x$key"))
        assertNull(auth.adminLogin(key.dropLast(1)))

        // Nothing about it reaches the disk: no auth.json appears just because the panel exists.
        assertFalse(File(dir, "auth.json").exists(), "starting up must not create auth.json")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a generated master key is new at every start, and the old one stops working`() {
        val dir = dir()
        val first = service(dir = dir, master = null)
        val firstKey = first.autoGeneratedMasterKey!!
        val second = service(dir = dir, master = null)
        val secondKey = second.autoGeneratedMasterKey!!

        assertTrue(firstKey != secondKey)
        assertNull(second.adminLogin(firstKey))
        assertNotNull(second.adminLogin(secondKey))
    }

    @Test
    fun `a master key left in an older auth file is ignored`() {
        val dir = dir()
        File(dir, "auth.json").writeText(
            """{"masterKey":{"salt":"c2FsdA==","hash":"aGFzaA==","iterations":120000},"generatedMasterKey":"old-key-from-a-file","password":null}""",
        )
        val auth = service(dir = dir, master = null)
        assertNull(auth.adminLogin("old-key-from-a-file"))
        assertNotNull(auth.adminLogin(auth.autoGeneratedMasterKey!!))
    }

    @Test
    fun `changing the password creates auth json, and the master key is never in it`() {
        val dir = dir()
        val auth = service(dir = dir, master = null)
        assertFalse(File(dir, "auth.json").exists())
        auth.changePassword("Some-new-password-1", false)

        val text = File(dir, "auth.json").readText()
        assertTrue("\"passwordText\"" in text)
        assertFalse(auth.autoGeneratedMasterKey!! in text, "the master key is never written")
    }

    @Test
    fun `a master key from the environment is used and nothing is generated`() {
        val dir = dir()
        val withEnv = service(dir = dir, master = "env-master-key-456")
        assertNotNull(withEnv.adminLogin("env-master-key-456"))
        assertNull(withEnv.autoGeneratedMasterKey)
        assertFalse(File(dir, "auth.json").exists())
    }

    @Test
    fun `admin sessions are separate from user sessions`() {
        val auth = service()
        val (adminToken, _) = auth.adminLogin("master-key-for-tests")!!
        assertFalse(auth.accepts(adminToken), "an admin token is not a way into the chat server")
        val userToken = (auth.login("server-password") as LoginResult.Success).token
        assertNull(auth.adminSession(userToken), "a user token is not a way into the panel")
        assertNotNull(auth.adminSession(adminToken))
    }

    @Test
    fun `admin sessions end after being idle`() {
        val auth = service(settings = AuthSettings(adminIdleMillis = 1_000, adminMaxMillis = 10_000))
        val (token, _) = auth.adminLogin("master-key-for-tests")!!
        now += 1_500
        assertNull(auth.adminSession(token))
    }

    @Test
    fun `the generated master key is made of unambiguous characters`() {
        val key = service(master = null).autoGeneratedMasterKey!!
        assertTrue(key.none { it in "0OIl1" }, key)
    }
}
