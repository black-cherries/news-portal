// Generates the app icons in public/icons from one simple design: a white
// page with headline and text lines on the accent colour. Run with
//   node scripts/make-icons.mjs
// No dependencies: PNGs are written with node:zlib.
import { mkdirSync, writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'

const ACCENT = [0x0b, 0x5c, 0xad]
const PAGE = [0xff, 0xff, 0xff]
const INK = [0x0b, 0x3d, 0x73]

// The design on a 512 × 512 grid: [x0, y0, x1, y1, colour]
const SHAPES = [
  [128, 104, 384, 408, PAGE],
  [160, 144, 352, 184, INK],
  [160, 216, 352, 232, INK],
  [160, 256, 352, 272, INK],
  [160, 296, 352, 312, INK],
  [160, 336, 288, 352, INK],
]

/** @param maskable full-bleed background, design shrunk into the 80% safe zone */
function pixel(u, v, maskable) {
  if (!maskable) {
    // Rounded corners, transparent outside.
    const r = 96
    const cx = Math.min(Math.max(u, r), 512 - r)
    const cy = Math.min(Math.max(v, r), 512 - r)
    if ((u - cx) ** 2 + (v - cy) ** 2 > r * r) return [0, 0, 0, 0]
  } else {
    u = 256 + (u - 256) / 0.8
    v = 256 + (v - 256) / 0.8
  }
  for (const [x0, y0, x1, y1, colour] of SHAPES.slice().reverse()) {
    if (u >= x0 && u < x1 && v >= y0 && v < y1) return [...colour, 255]
  }
  return [...ACCENT, 255]
}

function png(size, maskable) {
  const raw = Buffer.alloc((size * 4 + 1) * size)
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0 // filter: none
    for (let x = 0; x < size; x++) {
      const [r, g, b, a] = pixel(((x + 0.5) * 512) / size, ((y + 0.5) * 512) / size, maskable)
      raw.set([r, g, b, a], y * (size * 4 + 1) + 1 + x * 4)
    }
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header.set([8, 6, 0, 0, 0], 8) // 8-bit RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

function crc32(buffer) {
  let c = ~0
  for (const byte of buffer) {
    c ^= byte
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  }
  return ~c >>> 0
}

const dir = new URL('../public/icons/', import.meta.url)
mkdirSync(dir, { recursive: true })
writeFileSync(new URL('icon-192.png', dir), png(192, false))
writeFileSync(new URL('icon-512.png', dir), png(512, false))
writeFileSync(new URL('icon-maskable-512.png', dir), png(512, true))
writeFileSync(new URL('apple-touch-icon.png', dir), png(180, true))
console.log('Icons written to public/icons')
