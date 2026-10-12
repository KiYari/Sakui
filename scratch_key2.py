import os
os.chdir(r'C:\Users\win\IdeaProjects\eeck\web')


def sub(p, pairs):
    s = open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    s = s.replace('\r\n', '\n')
    for a, b in pairs:
        assert a in s, (p, a)
        s = s.replace(a, b, 1)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', newline='').write(s)


sub('src/shared/lib/server-target/__tests__/serverTarget.test.ts', [
    ("import { endpointFor, forgetServer,", "import { endpointFor, forgetKey, forgetServer, keyFor, rememberKey,"),
])
with open('src/shared/lib/server-target/__tests__/serverTarget.test.ts', 'a', encoding='utf-8', newline='') as f:
    f.write("""
describe('access keys', () => {
    it('are remembered per server and forgotten with it', () => {
        const storage = memoryStorage()
        expect(keyFor('1.2.3.4:3001', storage)).toBeUndefined()
        rememberKey('1.2.3.4:3001', 'secret', storage)
        rememberKey('5.6.7.8:3001', 'other', storage)
        expect(keyFor('1.2.3.4:3001', storage)).toBe('secret')
        forgetKey('1.2.3.4:3001', storage)
        expect(keyFor('1.2.3.4:3001', storage)).toBeUndefined()
        expect(keyFor('5.6.7.8:3001', storage)).toBe('other')

        forgetServer('5.6.7.8:3001', storage)
        expect(keyFor('5.6.7.8:3001', storage)).toBeUndefined()
    })

    it('ignore corrupted storage', () => {
        const storage = memoryStorage()
        storage.setItem('eeck-server-keys', '["not","a map"]')
        expect(keyFor('x', storage)).toBeUndefined()
        rememberKey('x', 'k', storage)
        expect(keyFor('x', storage)).toBe('k')
    })
})
""")

# The key store itself.
with open('src/shared/lib/server-target/serverTarget.ts', 'a', encoding='utf-8', newline='') as f:
    f.write("""
/** What [parseServer] yields for a same-origin build, which has no address to enter. */
export const SAME_ORIGIN_SERVER = ''

const KEYS_KEY = 'eeck-server-keys'

function readKeys(storage: KeyValueStorage | undefined): Record<string, string> {
    const stored = readJson(storage, KEYS_KEY)
    if (typeof stored !== 'object' || stored === null || Array.isArray(stored)) return {}
    return Object.fromEntries(Object.entries(stored).filter((e): e is [string, string] => typeof e[1] === 'string'))
}

/**
 * The access key last accepted by [server], if any. Kept in this browser only
 * and never put in a link: the link is shared, the key is the secret that
 * decides who may use the server at all.
 */
export function keyFor(server: string, storage: KeyValueStorage | undefined = browserStorage()): string | undefined {
    return readKeys(storage)[server] || undefined
}

export function rememberKey(server: string, key: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    writeJson(storage, KEYS_KEY, { ...readKeys(storage), [server]: key })
}

export function forgetKey(server: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    const rest = readKeys(storage)
    delete rest[server]
    writeJson(storage, KEYS_KEY, rest)
}
""")
sub('src/shared/lib/server-target/serverTarget.ts', [
    ("export function forgetServer(server: string, storage: KeyValueStorage | undefined = browserStorage()): void {\n",
     "export function forgetServer(server: string, storage: KeyValueStorage | undefined = browserStorage()): void {\n    forgetKey(server, storage)\n"),
])
sub('src/shared/lib/server-target/index.ts', [
    ("    endpointFor,\n    forgetServer,", "    endpointFor,\n    forgetKey,\n    forgetServer,"),
    ("    parseServerMode,\n", "    keyFor,\n    parseServerMode,\n"),
    ("    rememberServer,\n", "    rememberKey,\n    rememberServer,\n    SAME_ORIGIN_SERVER,\n"),
])
sub('src/shared/api/chat-link/chatLinkApi.ts', [
    ("if (res.status === 429) throw new Error('Too many chats created from here. Try again in a minute.')",
     "if (res.status === 429) throw new Error(body?.error ?? 'Too many requests from here. Try again in a minute.')"),
])
