// Runs in <head>, before the first paint, so a saved theme never flashes the wrong colours.
// Lets the stylesheet tell "scripts available" from "not" (see .no-js-only).
document.documentElement.classList.add('js')

try {
    var saved = localStorage.getItem('eeck-admin-theme')
    if (saved === 'light' || saved === 'dark') document.documentElement.setAttribute('data-theme', saved)
} catch (e) {
    /* storage can be blocked; the page then simply follows the system theme */
}
