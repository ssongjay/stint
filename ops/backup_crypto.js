// Binary authenticated backup transform. Secret key arrives on stdin, never argv.
const fs = require('node:fs');
const crypto = require('node:crypto');
const MAGIC = Buffer.from('STINTBK1');

try {
  const input = fs.readFileSync(0);
  const boundary = input.indexOf(10);
  if (boundary < 0 || boundary > 2048) throw new Error('Invalid header');
  const header = JSON.parse(input.subarray(0, boundary).toString('utf8'));
  if (!/^[a-f0-9]{64}$/.test(header.keyHex)) throw new Error('Invalid key');
  const key = Buffer.from(header.keyHex, 'hex');
  const data = input.subarray(boundary + 1);
  let result;
  if (header.action === 'encrypt') {
    const nonce = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce, {authTagLength: 16});
    cipher.setAAD(MAGIC);
    const ciphertext = Buffer.concat([cipher.update(data), cipher.final()]);
    result = Buffer.concat([MAGIC, nonce, cipher.getAuthTag(), ciphertext]);
  } else if (header.action === 'decrypt') {
    if (data.length < 36 || !data.subarray(0, 8).equals(MAGIC)) throw new Error('Invalid backup');
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, data.subarray(8, 20), {authTagLength: 16});
    decipher.setAAD(MAGIC);
    decipher.setAuthTag(data.subarray(20, 36));
    // No plaintext is emitted until final() verifies the authentication tag.
    result = Buffer.concat([decipher.update(data.subarray(36)), decipher.final()]);
  } else {
    throw new Error('Invalid action');
  }
  fs.writeFileSync(1, result);
} catch (_) {
  process.stderr.write('Backup authentication or format check failed.\n');
  process.exitCode = 1;
}
