/** Literal parsing only: no DNS, forwarding-chain fallback or persistent IP storage. */
function ipv4(value: string): number[] | null {
  const parts = value.split('.');
  if (parts.length !== 4 || parts.some(part => !/^(0|[1-9][0-9]{0,2})$/.test(part))) return null;
  const bytes = parts.map(Number);
  return bytes.some(byte => byte > 255) ? null : bytes;
}

function literal(value: string | null): string | null {
  if (!value || value.length > 45 || value.trim() !== value) return null;
  if (!value.includes(':')) {
    const bytes = ipv4(value);
    // Cloudflare Pseudo IPv4 overwrite is not a supported visitor identity source.
    return bytes && bytes[0] < 240 ? bytes.join('.') : null;
  }
  if (!/^[0-9a-fA-F:.]+$/.test(value)) return null;
  let text = value;
  if (text.includes('.')) {
    const split = text.lastIndexOf(':');
    const tail = ipv4(text.slice(split + 1));
    if (!tail) return null;
    text = `${text.slice(0, split + 1)}${((tail[0] << 8) | tail[1]).toString(16)}:${((tail[2] << 8) | tail[3]).toString(16)}`;
  }
  const halves = text.split('::');
  if (halves.length > 2) return null;
  const left = halves[0] === '' ? [] : halves[0].split(':');
  const right = halves.length === 1 || halves[1] === '' ? [] : halves[1].split(':');
  if ([...left, ...right].some(word => !/^[0-9a-fA-F]{1,4}$/.test(word))) return null;
  const missing = 8 - left.length - right.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  const words = [...left.map(word => parseInt(word, 16)), ...Array<number>(missing).fill(0), ...right.map(word => parseInt(word, 16))];
  if (words.slice(0, 5).every(word => word === 0) && words[5] === 0xffff) {
    const bytes = [words[6] >> 8, words[6] & 255, words[7] >> 8, words[7] & 255];
    return bytes[0] < 240 ? bytes.join('.') : null;
  }
  const normalized = words.map(word => word.toString(16)).join(':');
  // Cloudflare's cross-zone Worker subrequest address is not a visitor address.
  return normalized === '2a06:98c0:3600:0:0:0:0:103' ? null : normalized;
}

export function edgeClientAddress(headers: Headers): string | null {
  // Only direct browser -> Pages is supported. Worker subrequests and Pseudo
  // IPv4 overwrite have different CF-Connecting-IP semantics; fail closed for login.
  if (headers.has('CF-Worker') || headers.has('CF-Connecting-IPv6')) return null;
  return literal(headers.get('CF-Connecting-IP'));
}
