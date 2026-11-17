import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const ALLOWED_UID_RE = /^[A-Za-z0-9_\-]{1,128}$/;
const ALLOWED_PROVIDER_RE = /^[a-z0-9_]{2,48}$/;
const INVISIBLE_UNICODE_RE = /[\u200B-\u200D\uFEFF\u00AD\r\n]/g;

export class VaultError extends Error {
  constructor(message, statusCode = 500, errorCode = "CONFIGURATION_ERROR") {
    super(message);
    this.name = "VaultError";
    this.statusCode = statusCode;
    this.errorCode = errorCode;
  }
}

export function normalizeCredentialInput(rawValue, expectedEnvKey = "") {
  let cleaned = String(rawValue || "")
    .replace(INVISIBLE_UNICODE_RE, "")
    .trim();

  if (!cleaned) return "";

  if (expectedEnvKey) {
    const envPrefixRe = new RegExp(`^${expectedEnvKey}\\s*=\\s*`, "i");
    cleaned = cleaned.replace(envPrefixRe, "").trim();
  }

  if (
    (cleaned.startsWith('"') && cleaned.endsWith('"')) ||
    (cleaned.startsWith("'") && cleaned.endsWith("'"))
  ) {
    cleaned = cleaned.slice(1, -1).trim();
  }

  cleaned = cleaned.replace(/^Bearer\s+/i, "").trim();

  if (
    (cleaned.startsWith('"') && cleaned.endsWith('"')) ||
    (cleaned.startsWith("'") && cleaned.endsWith("'"))
  ) {
    cleaned = cleaned.slice(1, -1).trim();
  }

  return cleaned;
}

export function maskSecretHint(secret) {
  const clean = normalizeCredentialInput(secret);
  if (!clean) return "";
  if (clean.length <= 8) return "••••";
  return `${clean.slice(0, 4)}••••${clean.slice(-4)}`;
}

export function createProviderKeyVault(options = {}) {
  const env = options.env || process.env;
  const vaultDir = options.vaultDir || env.MYRAA_KEY_VAULT_DIR || path.join(os.tmpdir(), "myraa-provider-vault");

  let cachedMasterKey = null;
  let masterKeySource = "UNINITIALIZED";

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
      masterKeySource = "ENV_SECRET";
      return cachedMasterKey;
    }

    ensureVaultDir();
    const keyFile = path.join(vaultDir, ".vault-master.key");
    if (fs.existsSync(keyFile)) {
      const raw = fs.readFileSync(keyFile);
      if (raw.length === 32) {
        cachedMasterKey = raw;
        masterKeySource = "VAULT_KEY_FILE";
        return cachedMasterKey;
      }
    }
    const generated = crypto.randomBytes(32);
    fs.writeFileSync(keyFile, generated, { mode: 0o600 });
    cachedMasterKey = generated;
    masterKeySource = "VAULT_KEY_FILE";
    return cachedMasterKey;
  }

  function userVaultPath(userId) {
    const cleanUid = String(userId || "").trim();
    if (!ALLOWED_UID_RE.test(cleanUid)) {
      throw new VaultError("Invalid authenticated user identifier.", 400, "CONFIGURATION_ERROR");
    }
    ensureVaultDir();
    return path.join(vaultDir, `${cleanUid}.enc.json`);
  }

  function readUserMap(userId) {
    if (!userId) return {};
    const filePath = userVaultPath(userId);
    if (!fs.existsSync(filePath)) return {};
    let envelope;
    try {
      envelope = JSON.parse(fs.readFileSync(filePath, "utf8"));
    } catch (err) {
      throw new VaultError(
        `Encrypted API key vault file could not be parsed (${err?.message || "corrupt envelope"}).`,
        500,
        "CONFIGURATION_ERROR"
      );
    }
    if (!envelope?.iv || !envelope?.tag || !envelope?.ciphertext) {
      throw new VaultError(
        "Encrypted API key vault envelope is missing AES-GCM authentication fields.",
        500,
        "CONFIGURATION_ERROR"
      );
    }
    try {
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
    } catch (err) {
      throw new VaultError(
        "Failed to decrypt stored API keys. Verify MYRAA_KEY_VAULT_SECRET has not rotated without re-saving keys.",
        500,
        "CONFIGURATION_ERROR"
      );
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
    const tmpPath = `${filePath}.tmp.${process.pid}.${Date.now()}`;
    fs.writeFileSync(tmpPath, JSON.stringify(envelope), { mode: 0o600 });
    fs.renameSync(tmpPath, filePath);
  }

  function setProviderCredentials(userId, providerId, { apiKey, accountId = "", envKey = "" }) {
    const cleanProvider = String(providerId || "").trim();
    if (!ALLOWED_PROVIDER_RE.test(cleanProvider)) {
      throw new VaultError("Invalid provider identifier.", 400, "INVALID_PROVIDER");
    }
    const cleanKey = normalizeCredentialInput(apiKey, envKey);
    if (cleanKey.length < 8 || cleanKey.length > 512 || /\s/.test(cleanKey)) {
      throw new VaultError(
        "API key format looks invalid (must be 8-512 non-whitespace characters).",
        400,
        "INVALID_KEY_FORMAT"
      );
    }
    const cleanAccountId = normalizeCredentialInput(accountId).slice(0, 128);
    let map = {};
    try {
      map = readUserMap(userId);
    } catch (err) {
      if (err instanceof VaultError) {
        throw err;
      }
      map = {};
    }
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
    const normalizedKey = normalizeCredentialInput(entry.apiKey);
    const normalizedAccountId = normalizeCredentialInput(entry.accountId || "");
    if (!normalizedKey) return null;
    return {
      apiKey: normalizedKey,
      accountId: normalizedAccountId,
      maskedHint: String(entry.maskedHint || maskSecretHint(normalizedKey)),
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

  function getDiagnostics() {
    const configuredSecret = (options.vaultSecret || env.MYRAA_KEY_VAULT_SECRET || "").trim();
    const resolvedEnv = configuredSecret.length >= 16;
    return {
      masterKeySource: resolvedEnv ? "ENV_SECRET" : (masterKeySource === "UNINITIALIZED" ? "VAULT_KEY_FILE" : masterKeySource),
      persistentMasterKey: resolvedEnv || Boolean(env.MYRAA_KEY_VAULT_DIR),
      vaultDirConfigured: Boolean(options.vaultDir || env.MYRAA_KEY_VAULT_DIR)
    };
  }

  return {
    setProviderCredentials,
    getProviderCredentials,
    getProviderMetadata,
    deleteProviderCredentials,
    getDiagnostics
  };
}
