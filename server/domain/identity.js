import { randomBytes, scrypt, timingSafeEqual, randomUUID } from 'node:crypto';
import { promisify } from 'node:util';

const alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const KDF={N:16384,r:8,p:1,maxmem:64*1024*1024};
const scryptAsync=promisify(scrypt);
export function normalizeNickname(value) {
  const nickname=String(value??'').normalize('NFC').trim().replace(/\s+/gu,' ');
  const length=[...nickname].length;
  if(length<2||length>20||!/^[\p{L}\p{N} _.-]+$/u.test(nickname)) throw Object.assign(new Error('Nickname must be 2–20 letters, numbers, spaces, dots, dashes, or underscores.'),{status:400});
  return nickname;
}
export function createSecret(bytes=32) { return randomBytes(bytes).toString('base64url'); }
export function createPublicCode() {
  const bytes=randomBytes(8); let out='';
  for(const byte of bytes) out+=alphabet[byte%alphabet.length];
  return `${out.slice(0,4)}-${out.slice(4)}`;
}
export function createPlayerId() { return randomUUID(); }
export function createRecoveryCode() {
  // Sixteen uniformly selected symbols from a 32-character alphabet provide
  // 80 bits of recovery entropy and a fixed format that is easy to transcribe.
  const raw=Array.from(randomBytes(16),byte=>alphabet[byte&31]).join('');
  return raw.match(/.{4}/g).join('-');
}
export async function hashSecret(secret,salt=randomBytes(16).toString('base64url')) {
  const digest=(await scryptAsync(String(secret),salt,32,KDF)).toString('base64url');
  return {salt,hash:digest};
}
export async function verifySecret(secret,salt,expected) {
  try {
    const actual=await scryptAsync(String(secret),salt,32,KDF); const target=Buffer.from(expected,'base64url');
    return actual.length===target.length&&timingSafeEqual(actual,target);
  } catch { return false; }
}
export function normalizeRecoveryCode(value) { return String(value??'').toUpperCase().replace(/[^A-Z0-9]/g,''); }
