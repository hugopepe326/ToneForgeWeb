// Pure-JS zero-dependency ZIP archive generator with IEEE 802.3 CRC-32
(function (global) {
  'use strict';

  const CRC_TABLE = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    CRC_TABLE[i] = c >>> 0;
  }

  function crc32(bytes) {
    let crc = 0xFFFFFFFF;
    for (let i = 0; i < bytes.length; i++) {
      crc = CRC_TABLE[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
    }
    return (crc ^ 0xFFFFFFFF) >>> 0;
  }

  function toUtf8Bytes(input) {
    if (input instanceof Uint8Array) return input;
    if (input instanceof ArrayBuffer) return new Uint8Array(input);
    return new TextEncoder().encode(String(input));
  }

  function buildZip(entries) {
    // entries: Array<{ name: string, data: Uint8Array | ArrayBuffer | string }>
    const localParts = [];
    const centralParts = [];
    let offset = 0;

    const now = new Date();
    const dosTime = ((now.getHours() & 0x1F) << 11) |
                    ((now.getMinutes() & 0x3F) << 5) |
                    ((now.getSeconds() >> 1) & 0x1F);
    const dosDate = (((Math.max(1980, now.getFullYear()) - 1980) & 0x7F) << 9) |
                    (((now.getMonth() + 1) & 0x0F) << 5) |
                    (now.getDate() & 0x1F);

    for (const entry of entries) {
      const nameBytes = toUtf8Bytes(entry.name);
      const dataBytes = toUtf8Bytes(entry.data);
      const crc = crc32(dataBytes);
      const size = dataBytes.length;

      // Local File Header (30 bytes + name)
      const localHeader = new ArrayBuffer(30 + nameBytes.length);
      const lv = new DataView(localHeader);
      lv.setUint32(0, 0x04034B50, true); // Signature
      lv.setUint16(4, 20, true);         // Version needed (2.0)
      lv.setUint16(6, 0x0800, true);     // UTF-8 filename flag
      lv.setUint16(8, 0, true);          // Compression: 0 = STORE
      lv.setUint16(10, dosTime, true);
      lv.setUint16(12, dosDate, true);
      lv.setUint32(14, crc, true);
      lv.setUint32(18, size, true);
      lv.setUint32(22, size, true);
      lv.setUint16(26, nameBytes.length, true);
      lv.setUint16(28, 0, true);         // Extra len
      new Uint8Array(localHeader, 30).set(nameBytes);

      const localBytes = new Uint8Array(localHeader);
      localParts.push(localBytes, dataBytes);

      // Central Directory Header (46 bytes + name)
      const centralHeader = new ArrayBuffer(46 + nameBytes.length);
      const cv = new DataView(centralHeader);
      cv.setUint32(0, 0x02014B50, true); // Signature
      cv.setUint16(4, 20, true);         // Version made by
      cv.setUint16(6, 20, true);         // Version needed
      cv.setUint16(8, 0x0800, true);     // UTF-8 flag
      cv.setUint16(10, 0, true);         // Compression: STORE
      cv.setUint16(12, dosTime, true);
      cv.setUint16(14, dosDate, true);
      cv.setUint32(16, crc, true);
      cv.setUint32(20, size, true);
      cv.setUint32(24, size, true);
      cv.setUint16(28, nameBytes.length, true);
      cv.setUint16(30, 0, true);
      cv.setUint16(32, 0, true);
      cv.setUint16(34, 0, true);
      cv.setUint16(36, 0, true);
      cv.setUint32(38, 0, true);
      cv.setUint32(42, offset, true);    // Local header offset
      new Uint8Array(centralHeader, 46).set(nameBytes);

      centralParts.push(new Uint8Array(centralHeader));
      offset += localBytes.length + dataBytes.length;
    }

    let centralSize = 0;
    for (const c of centralParts) centralSize += c.length;

    // End of Central Directory (22 bytes)
    const eocd = new ArrayBuffer(22);
    const ev = new DataView(eocd);
    ev.setUint32(0, 0x06054B50, true);
    ev.setUint16(4, 0, true);
    ev.setUint16(6, 0, true);
    ev.setUint16(8, entries.length, true);
    ev.setUint16(10, entries.length, true);
    ev.setUint32(12, centralSize, true);
    ev.setUint32(16, offset, true);
    ev.setUint16(20, 0, true);

    const totalSize = offset + centralSize + 22;
    const out = new Uint8Array(totalSize);
    let pos = 0;
    for (const p of localParts) {
      out.set(p, pos);
      pos += p.length;
    }
    for (const c of centralParts) {
      out.set(c, pos);
      pos += c.length;
    }
    out.set(new Uint8Array(eocd), pos);
    return out;
  }

  global.ToneZip = { buildZip };
})(window);
