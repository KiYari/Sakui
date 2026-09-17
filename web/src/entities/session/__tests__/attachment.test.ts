import { describe, expect, it } from 'vitest'
import { base64ToBytes, bytesToBase64 } from '../../../shared/lib/crypto'
import {
    assembleChunks,
    chunkCountFor,
    isRenderableImage,
    safeMimeType,
    sanitizeFileName,
    CHUNK_BYTES,
} from '../model/attachment'

/** Mirrors the send path: slice, base64, then reassemble on the far side. */
function roundTrip(bytes: Uint8Array): ArrayBuffer {
    const total = chunkCountFor(bytes.byteLength)
    const parts = new Map<number, Uint8Array>()
    for (let i = 0; i < total; i++) {
        const wire = bytesToBase64(bytes.subarray(i * CHUNK_BYTES, (i + 1) * CHUNK_BYTES))
        parts.set(i, base64ToBytes(wire))
    }
    return assembleChunks(parts, total)
}

describe('attachment chunking', () => {
    it('every chunk stays under the server frame cap once framed as JSON', () => {
        const frame = JSON.stringify({
            type: 'message',
            body: {
                kind: 'file-chunk',
                fileId: crypto.randomUUID(),
                index: 999999,
                data: bytesToBase64(new Uint8Array(CHUNK_BYTES)),
            },
        })

        // DEFAULT_MAX_FRAME_SIZE on the server; exceeding it drops the connection.
        expect(frame.length).toBeLessThan(16 * 1024)
    })

    it('round-trips a payload that spans several chunks, byte for byte', () => {
        const original = crypto.getRandomValues(new Uint8Array(CHUNK_BYTES * 3 + 1234))

        const restored = new Uint8Array(roundTrip(original))

        expect(chunkCountFor(original.byteLength)).toBe(4)
        expect(restored).toEqual(original)
    })

    it('round-trips payloads at the awkward boundaries', () => {
        for (const size of [0, 1, CHUNK_BYTES - 1, CHUNK_BYTES, CHUNK_BYTES + 1]) {
            const original = crypto.getRandomValues(new Uint8Array(size))
            expect(new Uint8Array(roundTrip(original))).toEqual(original)
        }
    })

    it('refuses to assemble a transfer with a hole rather than returning short data', () => {
        const parts = new Map<number, Uint8Array>([
            [0, new Uint8Array([1, 2, 3])],
            [2, new Uint8Array([7, 8, 9])],
        ])

        expect(() => assembleChunks(parts, 3)).toThrow(/missing chunk 1/)
    })
})

describe('hostile sender handling', () => {
    it('never treats SVG as an inline image', () => {
        // An SVG is an XML document that can carry script; it must arrive as a
        // download, never as markup rendered on our own origin.
        expect(isRenderableImage('image/svg+xml')).toBe(false)
        expect(safeMimeType('image/svg+xml')).toBe('application/octet-stream')
    })

    it('honours only recognised image types and downgrades everything else', () => {
        expect(safeMimeType('image/png')).toBe('image/png')
        expect(safeMimeType('IMAGE/PNG')).toBe('image/png')
        expect(safeMimeType('text/html')).toBe('application/octet-stream')
        expect(safeMimeType('')).toBe('application/octet-stream')
    })

    it('strips path steering and control characters out of a filename', () => {
        // Separators become underscores, then the leading dots go too, so the
        // result can be neither a traversal nor a hidden file.
        expect(sanitizeFileName('../../etc/passwd')).toBe('_.._etc_passwd')
        expect(sanitizeFileName('C:\\Windows\\system32\\evil.exe')).toBe('C:_Windows_system32_evil.exe')
        expect(sanitizeFileName('photo\u0000.png')).toBe('photo.png')
        expect(sanitizeFileName('   ')).toBe('attachment')
        expect(sanitizeFileName('.'.repeat(10))).toBe('attachment')
        expect(sanitizeFileName('a'.repeat(300)).length).toBe(120)
    })
})
