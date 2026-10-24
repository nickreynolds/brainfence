// Generates resources/icon.png, icon.ico, tray.png and tray@2x.png — the
// Android launcher icon (white "B" on #6650A4) — with no image dependencies.
import { writeFileSync, mkdirSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'resources')
mkdirSync(out, { recursive: true })

const inCircle = (x, y, cx, cy, r) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r
const inRect = (x, y, x0, x1, y0, y1) => x >= x0 && x <= x1 && y >= y0 && y <= y1
const stadium = (x, y, x0, x1, y0, y1) => {
  const r = (y1 - y0) / 2
  return inRect(x, y, x0, x1, y0, y1) || inCircle(x, y, x1, y0 + r, r)
}
// Letter geometry in the Android vector's 108-unit viewport.
function inB(x, y) {
  const body = stadium(x, y, 38, 60, 24, 56) || stadium(x, y, 38, 62, 56, 88)
  const holes = stadium(x, y, 47, 60, 32.5, 47.5) || stadium(x, y, 47, 62, 64.5, 79.5)
  return body && !holes
}
function inRoundedSquare(x, y, size, radius) {
  const cx = Math.min(Math.max(x, radius), size - radius)
  const cy = Math.min(Math.max(y, radius), size - radius)
  return (x - cx) ** 2 + (y - cy) ** 2 <= radius * radius
}

function render(size, { background = true } = {}) {
  const px = Buffer.alloc(size * size * 4)
  const ss = 4
  // Map the letter's 108-unit box so the B fills ~70% of the icon.
  const scale = background ? size / 80 : size / 68
  const offX = 58 - size / scale / 2
  const offY = 56 - size / scale / 2
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let bg = 0
      let fg = 0
      for (let sy = 0; sy < ss; sy++) {
        for (let sx = 0; sx < ss; sx++) {
          const fx = x + (sx + 0.5) / ss
          const fy = y + (sy + 0.5) / ss
          if (!background || inRoundedSquare(fx, fy, size, size * 0.22)) bg++
          if (inB(fx / scale + offX, fy / scale + offY)) fg++
        }
      }
      const n = ss * ss
      const i = (y * size + x) * 4
      const a = fg / n
      if (background) {
        const cov = bg / n
        px[i] = Math.round(0x66 * (1 - a) + 255 * a)
        px[i + 1] = Math.round(0x50 * (1 - a) + 255 * a)
        px[i + 2] = Math.round(0xa4 * (1 - a) + 255 * a)
        px[i + 3] = Math.round(255 * cov)
      } else {
        px[i] = px[i + 1] = px[i + 2] = 255
        px[i + 3] = Math.round(255 * a)
      }
    }
  }
  return encodePng(size, px)
}

function crc32(buf) {
  let c = ~0
  for (const b of buf) {
    c ^= b
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function encodePng(size, rgba) {
  const raw = Buffer.alloc((size * 4 + 1) * size)
  for (let y = 0; y < size; y++) rgba.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4)
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8
  ihdr[9] = 6
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}

function encodeIco(pngs) {
  const header = Buffer.alloc(6 + 16 * pngs.length)
  header.writeUInt16LE(0, 0)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(pngs.length, 4)
  let offset = header.length
  pngs.forEach(({ size, data }, i) => {
    const e = 6 + 16 * i
    header[e] = size >= 256 ? 0 : size
    header[e + 1] = size >= 256 ? 0 : size
    header.writeUInt16LE(1, e + 4)
    header.writeUInt16LE(32, e + 6)
    header.writeUInt32LE(data.length, e + 8)
    header.writeUInt32LE(offset, e + 12)
    offset += data.length
  })
  return Buffer.concat([header, ...pngs.map((p) => p.data)])
}

writeFileSync(join(out, 'icon.png'), render(512))
writeFileSync(join(out, 'icon.ico'), encodeIco([16, 24, 32, 48, 64, 128, 256].map((size) => ({ size, data: render(size) }))))
writeFileSync(join(out, 'tray.png'), render(16))
writeFileSync(join(out, 'tray@2x.png'), render(32))
console.log('Icons written to', out)
