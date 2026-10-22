// Small zip reader and writer: enough for .praline, .praline-world and project files.
// Writing stores entries uncompressed (pictures are already compressed); reading also inflates
// deflated entries, which is what the app's ZipOutputStream writes.
var Zip = (function () {
  var CRC = (function () {
    var t = new Uint32Array(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(bytes) {
    var c = 0xFFFFFFFF;
    for (var i = 0; i < bytes.length; i++) c = CRC[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  async function bytesOf(data) {
    if (data instanceof Uint8Array) return data;
    if (typeof data === 'string') return new TextEncoder().encode(data);
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    return new Uint8Array(await data.arrayBuffer());
  }

  /** entries: [{name, data: Blob|Uint8Array|string}] -> Blob (application/zip). */
  async function write(entries, type) {
    var parts = [], central = [], offset = 0;
    for (var i = 0; i < entries.length; i++) {
      var name = new TextEncoder().encode(entries[i].name);
      var body = await bytesOf(entries[i].data);
      var crc = crc32(body);
      var head = new DataView(new ArrayBuffer(30));
      head.setUint32(0, 0x04034b50, true); head.setUint16(4, 20, true); head.setUint16(6, 0x0800, true);
      head.setUint16(8, 0, true); head.setUint16(10, 0, true); head.setUint16(12, 0x21, true);
      head.setUint32(14, crc, true); head.setUint32(18, body.length, true); head.setUint32(22, body.length, true);
      head.setUint16(26, name.length, true); head.setUint16(28, 0, true);
      parts.push(head.buffer, name, body);
      var cd = new DataView(new ArrayBuffer(46));
      cd.setUint32(0, 0x02014b50, true); cd.setUint16(4, 20, true); cd.setUint16(6, 20, true); cd.setUint16(8, 0x0800, true);
      cd.setUint16(10, 0, true); cd.setUint16(12, 0, true); cd.setUint16(14, 0x21, true);
      cd.setUint32(16, crc, true); cd.setUint32(20, body.length, true); cd.setUint32(24, body.length, true);
      cd.setUint16(28, name.length, true); cd.setUint32(42, offset, true);
      central.push(cd.buffer, name);
      offset += 30 + name.length + body.length;
    }
    var cdSize = 0;
    for (var j = 0; j < central.length; j++) cdSize += central[j].byteLength;
    var end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true);
    end.setUint32(12, cdSize, true); end.setUint32(16, offset, true);
    return new Blob(parts.concat(central, [end.buffer]), { type: type || 'application/zip' });
  }

  async function inflate(bytes) {
    var ds = new DecompressionStream('deflate-raw');
    var out = new Blob([bytes]).stream().pipeThrough(ds);
    return new Uint8Array(await new Response(out).arrayBuffer());
  }

  /** Blob|ArrayBuffer -> {name: Uint8Array}. Throws on anything that isn't a readable zip. */
  async function read(data, limits) {
    limits = limits || {};
    var buf = data instanceof ArrayBuffer ? data : await data.arrayBuffer();
    var v = new DataView(buf), u = new Uint8Array(buf);
    var e = -1;
    for (var i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 65557); i--) {
      if (v.getUint32(i, true) === 0x06054b50) { e = i; break; }
    }
    if (e < 0) throw new Error('not a zip file');
    var count = v.getUint16(e + 10, true), p = v.getUint32(e + 16, true);
    if (limits.maxEntries && count > limits.maxEntries) throw new Error('too many files inside (' + count + ')');
    var out = {};
    for (var n = 0; n < count; n++) {
      if (v.getUint32(p, true) !== 0x02014b50) throw new Error('damaged zip');
      var method = v.getUint16(p + 10, true), csize = v.getUint32(p + 20, true), size = v.getUint32(p + 24, true);
      var nlen = v.getUint16(p + 28, true), xlen = v.getUint16(p + 30, true), clen = v.getUint16(p + 32, true);
      var local = v.getUint32(p + 42, true);
      var name = new TextDecoder().decode(u.subarray(p + 46, p + 46 + nlen));
      p += 46 + nlen + xlen + clen;
      if (name.endsWith('/')) continue;
      if (limits.maxEntry && size > limits.maxEntry) throw new Error(name + ' is too big');
      var start = local + 30 + v.getUint16(local + 26, true) + v.getUint16(local + 28, true);
      var raw = u.subarray(start, start + csize);
      if (method === 0) out[name] = raw.slice();
      else if (method === 8) out[name] = await inflate(raw);
      else throw new Error(name + ' uses a compression this editor cannot read');
    }
    return out;
  }

  return { write: write, read: read, crc32: crc32 };
})();
