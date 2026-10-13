// Small progressive enhancements. The panel is fully usable without this file.
;(function () {
    'use strict'

    // Theme: system -> explicit light/dark, remembered in this browser.
    var toggle = document.getElementById('theme-toggle')
    if (toggle) {
        toggle.addEventListener('click', function () {
            var root = document.documentElement
            var current = root.getAttribute('data-theme')
            if (!current) current = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
            var next = current === 'dark' ? 'light' : 'dark'
            root.setAttribute('data-theme', next)
            try {
                localStorage.setItem('eeck-admin-theme', next)
            } catch (e) {
                /* ignore */
            }
        })
    }

    // A select marked for auto-submit applies the moment a choice is made (the Apply button is for no-JS).
    document.querySelectorAll('form.autosubmit select').forEach(function (select) {
        select.addEventListener('change', function () {
            select.form.submit()
        })
    })

    // The server password: masked until clicked, fetched only then, masked (and forgotten) on the next click.
    document.querySelectorAll('.secret-toggle').forEach(function (button) {
        var text = button.querySelector('.secret-text')
        var hint = button.querySelector('.secret-hint')
        var mask = text.textContent
        var shown = false
        var busy = false

        function hide() {
            shown = false
            text.textContent = mask
            hint.textContent = 'Show'
            button.setAttribute('aria-pressed', 'false')
            button.classList.remove('revealed')
        }

        button.addEventListener('click', function () {
            if (busy) return
            if (shown) {
                hide()
                return
            }
            busy = true
            fetch(button.getAttribute('data-reveal-url'), {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: '_csrf=' + encodeURIComponent(button.getAttribute('data-csrf')),
            })
                .then(function (response) {
                    if (!response.ok) throw new Error('refused')
                    return response.text()
                })
                .then(function (password) {
                    shown = true
                    text.textContent = password
                    hint.textContent = 'Hide'
                    button.setAttribute('aria-pressed', 'true')
                    button.classList.add('revealed')
                })
                .catch(function () {
                    hint.textContent = 'Could not load'
                    setTimeout(function () {
                        if (!shown) hint.textContent = 'Show'
                    }, 2000)
                })
                .then(function () {
                    busy = false
                })
        })
    })

    // Copy buttons: data-copy holds the text, or data-copy-from names an element to read it from.
    document.querySelectorAll('[data-copy], [data-copy-from]').forEach(function (button) {
        button.addEventListener('click', function () {
            var source = button.getAttribute('data-copy-from')
            var text = source ? document.getElementById(source).textContent : button.getAttribute('data-copy')
            var done = function () {
                var label = button.querySelector('.label') || button
                var before = label.textContent
                label.textContent = 'Copied'
                setTimeout(function () {
                    label.textContent = before
                }, 1600)
            }
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text.trim()).then(done, function () {})
            }
        })
    })

    // Live countdowns. data-remaining is seconds left when the page was rendered, so the
    // browser's own clock never has to agree with the server's.
    var started = performance.now()
    var timers = Array.prototype.slice.call(document.querySelectorAll('[data-remaining]'))
    function format(seconds) {
        if (seconds <= 0) return 'expired'
        var d = Math.floor(seconds / 86400)
        var h = Math.floor((seconds % 86400) / 3600)
        var m = Math.floor((seconds % 3600) / 60)
        var s = seconds % 60
        var pad = function (n) {
            return n < 10 ? '0' + n : '' + n
        }
        if (d > 0) return d + 'd ' + h + 'h'
        return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s)
    }
    function tick() {
        var elapsed = Math.floor((performance.now() - started) / 1000)
        timers.forEach(function (el) {
            var left = Math.max(0, parseInt(el.getAttribute('data-remaining'), 10) - elapsed)
            el.textContent = format(left)
            el.classList.toggle('soon', left > 0 && left < 60)
        })
    }
    if (timers.length) {
        tick()
        setInterval(tick, 1000)
    }
})()
