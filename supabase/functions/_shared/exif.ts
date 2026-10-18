// Strips GPS-bearing metadata. JPEG APP1 (Exif, XMP) and PNG text / eXIf chunks.
// The Kotlin ExifStrip object does the same work for the in-memory app path.

const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const PNG_DROP = new Set(["eXIf", "tEXt", "iTXt", "zTXt"]);

export function stripMetadata(bytes: Uint8Array): Uint8Array {
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xd8) return stripJpeg(bytes);
  if (isPng(bytes)) return stripPng(bytes);
  return bytes;
}

export function containsExifMarker(bytes: Uint8Array): boolean {
  const head = new TextDecoder().decode(bytes.subarray(0, Math.min(bytes.length, 4096)));
  if (head.includes("Exif") || head.includes("http://ns.adobe.com/xap")) return true;
  return isPng(bytes) && pngHasChunk(bytes, "eXIf");
}

function stripJpeg(bytes: Uint8Array): Uint8Array {
  const out: number[] = [0xff, 0xd8];
  let i = 2;
  while (i + 1 < bytes.length) {
    if (bytes[i] !== 0xff) {
      push(out, bytes.subarray(i));
      break;
    }
    while (i < bytes.length && bytes[i] === 0xff) i++;
    if (i >= bytes.length) break;
    const marker = bytes[i++];
    if (marker === 0xd9 || marker === 0xda || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      out.push(0xff, marker);
      if (marker === 0xda || marker === 0xd9) {
        push(out, bytes.subarray(i));
        break;
      }
      continue;
    }
    if (i + 1 >= bytes.length) break;
    const length = (bytes[i] << 8) | bytes[i + 1];
    if (length < 2 || i + length > bytes.length) {
      push(out, bytes.subarray(i - 2));
      break;
    }
    const payload = bytes.subarray(i + 2, i + length);
    const header = new TextDecoder().decode(payload.subarray(0, Math.min(payload.length, 40)));
    const drop = marker === 0xe1 && (header.startsWith("Exif") || header.startsWith("http://ns.adobe.com/xap"));
    if (!drop) {
      out.push(0xff, marker);
      push(out, bytes.subarray(i, i + length));
    }
    i += length;
  }
  return Uint8Array.from(out);
}

function stripPng(bytes: Uint8Array): Uint8Array {
  const out: number[] = Array.from(PNG_SIGNATURE);
  let i = 8;
  while (i + 12 <= bytes.length) {
    const length = readInt(bytes, i);
    if (length < 0 || i + 12 + length > bytes.length) {
      push(out, bytes.subarray(i));
      break;
    }
    const type = new TextDecoder().decode(bytes.subarray(i + 4, i + 8));
    const end = i + 12 + length;
    if (!PNG_DROP.has(type)) push(out, bytes.subarray(i, end));
    i = end;
    if (type === "IEND") break;
  }
  return Uint8Array.from(out);
}

function isPng(bytes: Uint8Array): boolean {
  if (bytes.length < 8) return false;
  return PNG_SIGNATURE.every((byte, index) => bytes[index] === byte);
}

function pngHasChunk(bytes: Uint8Array, name: string): boolean {
  let i = 8;
  while (i + 8 <= bytes.length) {
    const length = readInt(bytes, i);
    if (length < 0 || i + 12 + length > bytes.length) return false;
    const type = new TextDecoder().decode(bytes.subarray(i + 4, i + 8));
    if (type === name) return true;
    i += 12 + length;
  }
  return false;
}

function readInt(bytes: Uint8Array, offset: number): number {
  if (offset + 4 > bytes.length) return -1;
  return (bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3];
}

function push(out: number[], bytes: Uint8Array): void {
  for (const byte of bytes) out.push(byte);
}
