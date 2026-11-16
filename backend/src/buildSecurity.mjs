import path from "node:path";

const MAX_FILES_PER_BUILD = 80;
const MAX_SINGLE_FILE_BYTES = 262_144;
const MAX_TOTAL_WORKSPACE_BYTES = 5_242_880;

const PACKAGE_NAME_REGEX = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
const VERSION_NAME_REGEX = /^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$/;

const FORBIDDEN_GRADLE_PATTERNS = [
  { pattern: /Runtime\.getRuntime\s*\(/i, reason: "Runtime.getRuntime() execution blocked in build scripts" },
  { pattern: /ProcessBuilder/i, reason: "ProcessBuilder execution blocked in build scripts" },
  { pattern: /\bexec\s*\(/i, reason: "Shell exec() blocked in build scripts" },
  { pattern: /\/bin\/(sh|bash|zsh)\b/i, reason: "Direct shell invocation blocked" },
  { pattern: /\b(curl|wget|nc|ncat)\s+/i, reason: "External network command invocation blocked in build scripts" },
  { pattern: /URLClassLoader|DexClassLoader|PathClassLoader/i, reason: "Dynamic class loader blocked" },
  { pattern: /System\.getenv\s*\(/i, reason: "System.getenv() inspection blocked in user Gradle scripts" },
  { pattern: /GroovyShell|ScriptEngine/i, reason: "Dynamic script evaluation blocked" }
];

const BLOCKED_PERMISSIONS = [
  "android.permission.REQUEST_INSTALL_PACKAGES",
  "android.permission.MANAGE_EXTERNAL_STORAGE",
  "android.permission.QUERY_ALL_PACKAGES"
];

export class BuildValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "BuildValidationError";
  }
}

export function sanitizeRelativePath(rawPath) {
  if (typeof rawPath !== "string" || rawPath.length === 0 || rawPath.length > 260) {
    throw new BuildValidationError("Invalid project file path length.");
  }
  if (rawPath.includes("\u0000") || /[\r\n]/.test(rawPath)) {
    throw new BuildValidationError("Illegal control characters in file path.");
  }

  const slashNormalized = rawPath.replace(/\\/g, "/").replace(/^\/+/, "");
  const segments = slashNormalized.split("/").filter(Boolean);
  if (segments.length === 0) {
    throw new BuildValidationError(`Empty relative file path: '${rawPath}'`);
  }
  for (const segment of segments) {
    if (segment === "." || segment === ".." || segment.includes("..")) {
      throw new BuildValidationError(`Path traversal detected in '${rawPath}'`);
    }
    if (!/^[A-Za-z0-9._\-+@]+$/.test(segment)) {
      throw new BuildValidationError(`Unsupported characters in path segment '${segment}'`);
    }
  }
  return segments.join("/");
}

export function resolveInsideWorkspace(workspaceDir, relativePath) {
  const safeRel = sanitizeRelativePath(relativePath);
  const resolvedBase = path.resolve(workspaceDir);
  const resolvedTarget = path.resolve(resolvedBase, safeRel);
  if (resolvedTarget !== resolvedBase && !resolvedTarget.startsWith(resolvedBase + path.sep)) {
    throw new BuildValidationError(`Resolved path escapes workspace: '${relativePath}'`);
  }
  return { safeRel, resolvedTarget };
}

export function validateProjectMetadata({ appName, packageName, versionName, versionCode }) {
  const cleanName = typeof appName === "string" ? appName.trim() : "";
  if (cleanName.length < 1 || cleanName.length > 60) {
    throw new BuildValidationError("appName must be 1 to 60 characters.");
  }
  if (!PACKAGE_NAME_REGEX.test(String(packageName || ""))) {
    throw new BuildValidationError(`Invalid Android packageName: '${packageName}'`);
  }
  if (!VERSION_NAME_REGEX.test(String(versionName || "1.0.0"))) {
    throw new BuildValidationError(`Invalid versionName: '${versionName}'`);
  }
  const codeNum = Number(versionCode ?? 1);
  if (!Number.isInteger(codeNum) || codeNum < 1 || codeNum > 1_000_000) {
    throw new BuildValidationError("versionCode must be an integer between 1 and 1,000,000.");
  }
}

export function inspectBuildFiles(rawFiles) {
  if (!Array.isArray(rawFiles) || rawFiles.length === 0) {
    throw new BuildValidationError("Build request must contain non-empty project files.");
  }
  if (rawFiles.length > MAX_FILES_PER_BUILD) {
    throw new BuildValidationError(`Too many files (${rawFiles.length} > ${MAX_FILES_PER_BUILD}).`);
  }

  let totalBytes = 0;
  const sanitized = new Map();

  for (const file of rawFiles) {
    if (!file || typeof file.path !== "string" || typeof file.content !== "string") {
      throw new BuildValidationError("Every file item requires string 'path' and 'content'.");
    }
    const cleanPath = sanitizeRelativePath(file.path);
    const bytes = Buffer.byteLength(file.content, "utf8");
    if (bytes > MAX_SINGLE_FILE_BYTES) {
      throw new BuildValidationError(`File exceeds 256 KB limit: '${cleanPath}'`);
    }
    totalBytes += bytes;
    if (totalBytes > MAX_TOTAL_WORKSPACE_BYTES) {
      throw new BuildValidationError("Total project payload exceeds 5 MB limit.");
    }

    if (cleanPath.endsWith(".gradle") || cleanPath.endsWith(".gradle.kts")) {
      for (const rule of FORBIDDEN_GRADLE_PATTERNS) {
        if (rule.pattern.test(file.content)) {
          throw new BuildValidationError(`Security policy violation in '${cleanPath}': ${rule.reason}`);
        }
      }
      if (/signingConfigs\s*\{/i.test(file.content) && /storePassword/i.test(file.content)) {
        // Ensure user-submitted Gradle does not reference external server filesystem paths outside workspace
        const match = file.content.match(/file\s*\(\s*["']([^"']+)["']\s*\)/);
        if (match && (match[1].startsWith("/") || match[1].includes(".."))) {
          throw new BuildValidationError(`Forbidden external keystore file reference in '${cleanPath}'`);
        }
      }
    }

    if (cleanPath === "app/src/main/AndroidManifest.xml") {
      for (const perm of BLOCKED_PERMISSIONS) {
        if (file.content.includes(perm)) {
          throw new BuildValidationError(`Restricted Play permission '${perm}' is prohibited in generated APKs.`);
        }
      }
    }

    // Never allow client-supplied local.properties or signing keystores to overwrite server sandbox config
    if (cleanPath === "local.properties" || cleanPath.endsWith(".jks") || cleanPath.endsWith(".keystore")) {
      continue;
    }

    sanitized.set(cleanPath, { path: cleanPath, content: file.content });
  }

  const requiredFiles = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml"
  ];
  for (const req of requiredFiles) {
    if (!sanitized.has(req)) {
      throw new BuildValidationError(`Missing required Android Gradle project file: '${req}'`);
    }
  }

  return Array.from(sanitized.values());
}
