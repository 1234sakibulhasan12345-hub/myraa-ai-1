import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const ALLOWED_UID_RE = /^[A-Za-z0-9_\-]{1,128}$/;
const ALLOWED_PROVIDER_RE = /^[a-z0-9_]{2,48}$/;

export function maskSecretHint(secret) {
  const clean = String(secret || "").trim();
  if (!clean) return "";
  if (clean.length <= 8) return "••••";
  return `${clean.slice(0, 4)}••••${clean.slice(-4)}`;
}

export function createProviderKeyVault(options = {}) {
  const env = options.env || process.env;
  const vaultDir = options.vaultDir || env.MYRAA_KEY_VAULT_DIR || path.join(os.tmpdir(), "myraa-provider-vault");

  let cachedMasterKey = null;

  function ensureVaultDir() {
    if (!fs.existsSync(vaultDir)) {
      fs.mkdirSync(vaultDir, { recursive: true, mode: 0o700 });
    }
    try {
      fs.chmodSync(vaultDir, 0o700);
    } catch {
      // Ignore chmod issues on unsupported filesystems
    }
  }

  function resolveMasterKey() {
    if (cachedMasterKey) return cachedMasterKey;
    const configuredSecret = (options.vaultSecret || env.MYRAA_KEY_VAULT_SECRET || "").trim();
    if (configuredSecret.length >= 16) {
      cachedMasterKey = crypto.createHash("sha256").update(configuredSecret).digest();
      return cachedMasterKey;
    }

    ensureVaultDir();
    const keyFile = path.join(vaultDir, ".vault-master.key");
    if (fs.existsSync(keyFile)) {
      const raw = fs.readFileSync(keyFile);
      if (raw.length === 32) {
        cachedMasterKey = raw;
        return cachedMasterKey;
      }
    }
    const generated = crypto.randomBytes(32);
    fs.writeFileSync(keyFile, generated, { mode: 0o600 });
    cachedMasterKey = generated;
    return cachedMasterKey;
  }

  function userVaultPath(userId) {
    const cleanUid = String(userId || "").trim();
    if (!ALLOWED_UID_RE.test(cleanUid)) {
      throw Object.assign(new Error("Invalid authenticated user identifier."), { statusCode: 400 });
    }
    ensureVaultDir();
    return path.join(vaultDir, `${cleanUid}.enc.json`);
  }

  function readUserMap(userId) {
    if (!userId) return {};
    const filePath = userVaultPath(userId);
    if (!fs.existsSync(filePath)) return {};
    try {
      const envelope = JSON.parse(fs.readFileSync(filePath, "utf8"));
      if (!envelope?.iv || !envelope?.tag || !envelope?.ciphertext) return {};
      const key = resolveMasterKey();
      const decipher = crypto.createDecipheriv(
        "aes-256-gcm",
        key,
        Buffer.from(envelope.iv, "base64")
      );
      decipher.setAuthTag(Buffer.from(envelope.tag, "base64"));
      const decrypted = Buffer.concat([
        decipher.update(Buffer.from(envelope.ciphertext, "base64")),
        decipher.final()
      ]).toString("utf8");
      const parsed = JSON.parse(decrypted);
      return parsed && typeof parsed === "object" ? parsed : {};
    } catch {
      return {};
    }
  }

  function writeUserMap(userId, map) {
    const filePath = userVaultPath(userId);
    const key = resolveMasterKey();
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
    const plaintext = Buffer.from(JSON.stringify(map), "utf8");
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const tag = cipher.getAuthTag();
    const envelope = {
      v: 1,
      iv: iv.toString("base64"),
      tag: tag.toString("base64"),
      ciphertext: ciphertext.toString("base64"),
      updatedAtEpochMs: Date.now()
    };
    fs.writeFileSync(filePath, JSON.stringify(envelope), { mode: 0o600 });
  }

  function setProviderCredentials(userId, providerId, { apiKey, accountId = "" }) {
    const cleanProvider = String(providerId || "").trim();
    if (!ALLOWED_PROVIDER_RE.test(cleanProvider)) {
      throw Object.assign(new Error("Invalid provider identifier."), { statusCode: 400 });
    }
    const cleanKey = String(apiKey || "").trim();
    if (cleanKey.length < 8 || cleanKey.length > 512) {
      throw Object.assign(new Error("API key length looks invalid (must be 8-512 characters)."), { statusCode: 400 });
    }
    const cleanAccountId = String(accountId || "").trim().slice(0, 128);
    const map = readUserMap(userId);
    map[cleanProvider] = {
      apiKey: cleanKey,
      accountId: cleanAccountId,
      maskedHint: maskSecretHint(cleanKey),
      updatedAtEpochMs: Date.now()
    };
    writeUserMap(userId, map);
    return {
      providerId: cleanProvider,
      configured: true,
      maskedHint: map[cleanProvider].maskedHint,
      hasAccountId: Boolean(cleanAccountId),
      updatedAtEpochMs: map[cleanProvider].updatedAtEpochMs
    };
  }

  function getProviderCredentials(userId, providerId) {
    if (!userId) return null;
    const map = readUserMap(userId);
    const entry = map[providerId];
    if (!entry || !entry.apiKey) return null;
    return {
      apiKey: String(entry.apiKey),
      accountId: String(entry.accountId || ""),
      maskedHint: String(entry.maskedHint || maskSecretHint(entry.apiKey)),
      updatedAtEpochMs: Number(entry.updatedAtEpochMs || 0)
    };
  }

  function getProviderMetadata(userId, providerId) {
    const creds = getProviderCredentials(userId, providerId);
    if (!creds) {
      return {
        configured: false,
        maskedHint: "",
        hasAccountId: false,
        updatedAtEpochMs: 0
      };
    }
    return {
      configured: true,
      maskedHint: creds.maskedHint,
      hasAccountId: Boolean(creds.accountId),
      updatedAtEpochMs: creds.updatedAtEpochMs
    };
  }

  function deleteProviderCredentials(userId, providerId) {
    if (!userId) return false;
    const map = readUserMap(userId);
    if (!map[providerId]) return false;
    delete map[providerId];
    writeUserMap(userId, map);
    return true;
  }

  return {
    setProviderCredentials,
    getProviderCredentials,
    getProviderMetadata,
    deleteProviderCredentials
  };
}
