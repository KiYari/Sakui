package com.eeck.server.features.auth.admin

import com.eeck.server.features.auth.model.AccessMode
import com.eeck.server.features.auth.service.AuthService
import com.eeck.server.features.auth.service.AuthStatus
import com.eeck.server.features.auth.service.CodeInfo
import com.eeck.server.features.auth.service.Flash
import com.eeck.server.features.auth.service.PasswordSource
import com.eeck.server.features.auth.service.SecretPolicy
import kotlinx.html.BODY
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe

/** Everything one rendering of the dashboard needs, so the template is a pure function of it. */
class DashboardModel(
    val status: AuthStatus,
    val codes: List<CodeInfo>,
    val flash: Flash?,
    val csrf: String,
    val nowMillis: Long,
)

private const val ASSETS = "/admin/assets"

/** What the password shows as until someone asks to see it. */
private const val MASK = "********"

// Trusted, static markup only: never built from request data.
private const val LOGO_SVG =
    """<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>"""
private const val SUN_SVG =
    """<svg class="sun" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>"""
private const val MOON_SVG =
    """<svg class="moon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/></svg>"""
private const val COPY_SVG =
    """<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>"""

private val TTL_CHOICES = listOf(
    300L to "5 minutes",
    900L to "15 minutes",
    3_600L to "1 hour",
    21_600L to "6 hours",
    86_400L to "24 hours",
    7 * 86_400L to "1 week",
    AuthService.DEFAULT_CODE_TTL_SECONDS to "1 month",
    90 * 86_400L to "3 months",
    AuthService.MAX_CODE_TTL_SECONDS to "1 year",
)

/** The page frame shared by every screen: head, fonts-free styling, theme, scripts. */
private fun HTML.frame(pageTitle: String, content: BODY.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1, viewport-fit=cover")
        meta(name = "color-scheme", content = "dark light")
        meta(name = "robots", content = "noindex, nofollow")
        title(pageTitle)
        link(rel = "stylesheet", href = "$ASSETS/admin.css")
        script(src = "$ASSETS/theme-init.js") {}
    }
    body {
        content()
        script(src = "$ASSETS/admin.js") { defer = true }
    }
}

private fun FlowContent.themeToggle() {
    button(classes = "secondary icon") {
        id = "theme-toggle"
        type = ButtonType.button
        title = "Switch theme"
        attributes["aria-label"] = "Switch theme"
        unsafe { +SUN_SVG; +MOON_SVG }
    }
}

private fun FlowContent.brand(tag: String) {
    div("brand") {
        span("logo") { unsafe { +LOGO_SVG } }
        h1 { +"eeck" }
        span("tag") { +tag }
    }
}

// ---- sign-in -----------------------------------------------------------------------------

fun HTML.loginPage(error: String?) = frame("eeck admin · sign in") {
    div("shell narrow") {
        div("topbar") {
            brand("admin")
            themeToggle()
        }
        div("card") {
            header {
                h2 { +"Sign in" }
                p("muted") { +"Enter the master key. It opens this panel and nothing else." }
            }
            error?.let { div("flash error") { +it } }
            form(action = "/admin/login", method = FormMethod.post) {
                label("field") {
                    span { +"Master key" }
                    passwordInput(name = "masterKey") {
                        autoComplete = "off"
                        required = true
                        autoFocus = true
                        placeholder = "EECK_MASTER_KEY"
                    }
                }
                button(type = ButtonType.submit) { +"Sign in" }
            }
        }
        p("muted") {
            +"Not set in .env? Then the server generated one for this run and printed it in its log at start; it changes on every restart. Set EECK_MASTER_KEY in .env for a fixed one."
        }
    }
}

fun HTML.tooManyAttemptsPage() = frame("eeck admin · slow down") {
    div("shell narrow") {
        div("card") {
            header {
                h2 { +"Too many attempts" }
                p("muted") { +"Wait a minute, then try again." }
            }
            a(href = "/admin", classes = "button") { +"Back" }
        }
    }
}

// ---- dashboard ---------------------------------------------------------------------------

fun HTML.dashboardPage(model: DashboardModel) = frame("eeck admin") {
    div("shell") {
        div("topbar") {
            brand("admin")
            div("actions") {
                themeToggle()
                form(action = "/admin/logout", method = FormMethod.post) {
                    csrf(model.csrf)
                    button(classes = "secondary", type = ButtonType.submit) { +"Sign out" }
                }
            }
        }

        model.flash?.let { flash(it) }
        if (!model.status.accessRequired) {
            div("flash warn") {
                +"This server accepts everyone: no password or code is asked for, and one-time codes have no effect. Switch Server access to Protected to require one."
            }
        }

        statsRow(model)

        div("grid") {
            div("stack") {
                passwordCard(model)
                signOutCard(model)
            }
            codesCard(model)
        }
    }
}

private fun FlowContent.flash(flash: Flash) {
    when (flash.kind) {
        Flash.Kind.SUCCESS -> div("flash success") { +flash.message }
        Flash.Kind.ERROR -> div("flash error") { +flash.message }
        Flash.Kind.CODE -> div("reveal") {
            p { +flash.message }
            div("secret") {
                span("code") {
                    id = "new-code"
                    +(flash.code ?: "")
                }
                button(classes = "secondary") {
                    type = ButtonType.button
                    attributes["data-copy-from"] = "new-code"
                    unsafe { +COPY_SVG }
                    span("label") { +"Copy" }
                }
            }
            p("muted") { +"This is the only time it is shown. It works once." }
        }
    }
}

private fun FlowContent.statsRow(model: DashboardModel) {
    val status = model.status
    div("stats") {
        accessStat(model)
        passwordStat(model)
        stat("Signed in", status.userSessions.toString(), "", "people on this server")
        stat("Active codes", status.activeCodes.toString(), "", "one-time, unspent")
    }
}

/**
 * The first tile doubles as the switch for who is let in. It is a plain form with a select, so it works
 * without scripts (the Apply button shows then); with scripts, choosing an option submits at once.
 */
private fun FlowContent.accessStat(model: DashboardModel) {
    val status = model.status
    val protectedNow = status.accessMode == AccessMode.PROTECTED
    div("stat") {
        div("label") { +"Server access" }
        form(action = "/admin/access", method = FormMethod.post, classes = "autosubmit") {
            csrf(model.csrf)
            div("select-wrap") {
                select(classes = if (protectedNow) "ok" else "warn") {
                    name = "mode"
                    attributes["aria-label"] = "Server access"
                    option {
                        value = "protected"
                        selected = protectedNow
                        +"Protected"
                    }
                    option {
                        value = "open"
                        selected = !protectedNow
                        +"All accepted"
                    }
                }
            }
            button(classes = "secondary small no-js-only", type = ButtonType.submit) { +"Apply" }
        }
        div("note") {
            +when {
                !protectedNow -> "anyone can join"
                status.hasPassword -> "password or code required"
                else -> "one-time codes only"
            }
        }
    }
}

/**
 * The server password, masked. It is not in this page at all: the button asks the server for it only
 * when clicked (see admin.js), shows it, and clicking again masks it and drops it from the page.
 */
private fun FlowContent.passwordStat(model: DashboardModel) {
    val status = model.status
    div("stat") {
        div("label") { +"Server password" }
        when {
            !status.hasPassword -> {
                div("value muted-value") { +"Not set" }
            }
            !status.passwordViewable -> {
                div("value muted-value") { +"Hidden" }
                div("note") { +"Set before it could be shown. Change it once to see it here." }
            }
            else -> button(classes = "secret-toggle", type = ButtonType.button) {
                attributes["data-reveal-url"] = "/admin/password/reveal"
                attributes["data-csrf"] = model.csrf
                attributes["aria-pressed"] = "false"
                attributes["title"] = "Click to show or hide"
                span("secret-text") { +MASK }
                span("secret-hint") { +"Show" }
            }
        }
    }
}

private fun FlowContent.stat(label: String, value: String, tone: String, note: String) {
    div("stat") {
        div("label") { +label }
        div("value $tone".trim()) { +value }
        div("note") { +note }
    }
}

private fun FlowContent.csrf(token: String) = hiddenInput(name = "_csrf") { value = token }

private fun FlowContent.passwordCard(model: DashboardModel) {
    div("card") {
        header {
            h2 { +"Server password" }
            p("muted") {
                +"What people type to join this server. Changing it takes effect immediately and is kept across restarts."
            }
        }
        form(action = "/admin/password", method = FormMethod.post) {
            csrf(model.csrf)
            label("field") {
                span { +"New password" }
                passwordInput(name = "newPassword") {
                    autoComplete = "off"
                    required = true
                    minLength = SecretPolicy.MIN_LENGTH.toString()
                    maxLength = SecretPolicy.MAX_LENGTH.toString()
                    placeholder = "At least ${SecretPolicy.MIN_LENGTH} characters"
                }
            }
            label("field") {
                span { +"Repeat it" }
                passwordInput(name = "confirm") {
                    autoComplete = "off"
                    required = true
                    maxLength = SecretPolicy.MAX_LENGTH.toString()
                }
            }
            label("check") {
                checkBoxInput(name = "signOut") { checked = true }
                span { +"Sign everyone out (they must use the new password)" }
            }
            button(type = ButtonType.submit) { +"Change password" }
        }
    }
}

private fun FlowContent.signOutCard(model: DashboardModel) {
    div("card") {
        header {
            h2 { +"Sessions" }
            p("muted") { +"Ends every signed-in session. People are asked for a password or a code again." }
        }
        form(action = "/admin/sessions/revoke-all", method = FormMethod.post) {
            csrf(model.csrf)
            button(classes = "danger", type = ButtonType.submit) { +"Sign everyone out" }
        }
    }
}

private fun FlowContent.codesCard(model: DashboardModel) {
    div("card") {
        header {
            h2 { +"One-time codes" }
            p("muted") {
                +"A code lets one person in, once, until it expires (from minutes up to a year). Leave the field empty to generate one."
            }
        }
        form(action = "/admin/codes", method = FormMethod.post) {
            csrf(model.csrf)
            div("row two") {
                label("field") {
                    span { +"Code (optional)" }
                    textInput(name = "code") {
                        autoComplete = "off"
                        maxLength = SecretPolicy.MAX_LENGTH.toString()
                        placeholder = "Generate automatically"
                        attributes["autocapitalize"] = "none"
                        attributes["spellcheck"] = "false"
                    }
                }
                label("field") {
                    span { +"Valid for" }
                    select {
                        name = "ttl"
                        for ((seconds, text) in TTL_CHOICES) {
                            option {
                                value = seconds.toString()
                                selected = seconds == AuthService.DEFAULT_CODE_TTL_SECONDS
                                +text
                            }
                        }
                    }
                }
            }
            label("field") {
                span { +"Note (who is it for?)" }
                textInput(name = "label") {
                    autoComplete = "off"
                    maxLength = "60"
                    placeholder = "Optional"
                }
            }
            button(type = ButtonType.submit) { +"Create code" }
        }

        if (model.codes.isEmpty()) {
            div("empty") { +"No active codes." }
        } else {
            div("table-wrap") {
                table {
                    thead {
                        tr {
                            th { +"Note" }
                            th { +"Kind" }
                            th { +"Expires in" }
                            th {}
                        }
                    }
                    tbody {
                        for (code in model.codes) codeRow(code, model)
                    }
                }
            }
        }
    }
}

/** Mirrors `format` in admin.js: days and hours for long codes, h:mm:ss or m:ss for short ones. */
internal fun formatRemaining(seconds: Long): String {
    if (seconds <= 0) return "expired"
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    val secs = seconds % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "$hours:${two(minutes)}:${two(secs)}"
        else -> "$minutes:${two(secs)}"
    }
}

private fun kotlinx.html.TBODY.codeRow(code: CodeInfo, model: DashboardModel) {
    val remaining = ((code.expiresAtMillis - model.nowMillis) / 1000).coerceAtLeast(0)
    tr {
        td {
            attributes["data-label"] = "Note"
            +(code.label.ifEmpty { "—" })
        }
        td {
            attributes["data-label"] = "Kind"
            span("pill") { +(if (code.custom) "typed" else "generated") }
        }
        td {
            attributes["data-label"] = "Expires in"
            span("left") {
                attributes["data-remaining"] = remaining.toString()
                +formatRemaining(remaining)
            }
        }
        td {
            form(action = "/admin/codes/${code.id}/revoke", method = FormMethod.post) {
                csrf(model.csrf)
                button(classes = "danger small", type = ButtonType.submit) { +"Revoke" }
            }
        }
    }
}
