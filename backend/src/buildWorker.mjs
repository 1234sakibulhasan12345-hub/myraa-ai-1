import { spawn } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  BuildValidationError,
  inspectBuildFiles,
  resolveInsideWorkspace,
  validateProjectMetadata
} from "./buildSecurity.mjs";

function appendBoundedLog(job, line) {
  const clean = String(line || "");
  if (!clean) return;
  job.logs.push(clean);
  if (job.logs.length > 500) {
    job.logs.splice(0, job.logs.length - 500);
  }
}

async function fileExists(filePath) {
  try {
    const stat = await fs.stat(filePath);
    return stat.isFile();
  } catch {
    return false;
  }
}

async function dirExists(dirPath) {
  try {
    const stat = await fs.stat(dirPath);
    return stat.isDirectory();
  } catch {
    return false;
  }
}

async function findApkFiles(rootDir) {
  const found = [];
  if (!(await dirExists(rootDir))) return found;
  const stack = [rootDir];
  while (stack.length > 0) {
    const current = stack.pop();
    const entries = await fs.readdir(current, { withFileTypes: true });
    for (const entry of entries) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
      } else if (entry.isFile() && entry.name.endsWith(".apk")) {
        found.push(fullPath);
      }
    }
  }
  return found;
}

async function verifyZipMagicAndSha256(apkPath) {
  const bytes = await fs.readFile(apkPath);
  if (bytes.length < 64) {
    throw new Error("APK file is empty or too small to be a valid Android package.");
  }
  // ZIP local file header magic bytes: PK\x03\x04
  if (bytes[0] !== 0x50 || bytes[1] !== 0x4b || bytes[2] !== 0x03 || bytes[3] !== 0x04) {
    throw new Error("Output artifact failed ZIP/APK magic header check.");
  }
  const hasManifest = bytes.includes(Buffer.from("AndroidManifest.xml"));
  const hasDex = bytes.includes(Buffer.from("classes.dex")) || bytes.includes(Buffer.from(".dex"));
  if (!hasManifest) {
    throw new Error("Output APK archive does not contain AndroidManifest.xml.");
  }
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  return { sizeBytes: bytes.length, sha256, hasDex };
}

function runSubprocess({ command, args, cwd, env, timeoutMs, onLog }) {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      cwd,
      env,
      stdio: ["ignore", "pipe", "pipe"]
    });

    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);

    const handleChunk = (chunk) => {
      const lines = chunk.toString("utf8").split(/\r?\n/);
      for (const line of lines) {
        if (line.trim()) onLog(line);
      }
    };

    child.stdout.on("data", handleChunk);
    child.stderr.on("data", handleChunk);

    child.on("error", (err) => {
      clearTimeout(timer);
      onLog(`[worker-error] ${err.message}`);
      resolve({ exitCode: -1, timedOut });
    });

    child.on("close", (code) => {
      clearTimeout(timer);
      resolve({ exitCode: code ?? -1, timedOut });
    });
  });
}

export function createBuildQueueManager(options = {}) {
  const env = options.env || process.env;
  const sdkRoot = options.androidSdkRoot || env.ANDROID_SDK_ROOT || "/opt/android/sdk";
  const gradleBin = options.gradleBin || env.GRADLE_BIN || "gradle";
  const apksignerBin =
    options.apksignerBin ||
    env.APKSIGNER_BIN ||
    path.join(sdkRoot, "build-tools", "36.0.0", "apksigner");
  const artifactDir = options.artifactDir || env.ARTIFACT_DIR || path.join(os.tmpdir(), "myraa-artifacts");
  const buildTimeoutMs = Number(options.buildTimeoutMs || env.BUILD_TIMEOUT_MS || 240_000);
  const artifactTtlMs = Number(options.artifactTtlMs || env.ARTIFACT_TTL_MS || 86_400_000);
  const maxConcurrentBuilds = Math.max(1, Number(options.maxConcurrentBuilds || env.MAX_CONCURRENT_BUILDS || 2));
  const gradleOffline = options.gradleOffline ?? (env.GRADLE_OFFLINE === "true");

  const jobs = new Map();
  const pendingQueue = [];
  let activeCount = 0;

  async function enqueueBuild({
    userId,
    projectId,
    buildType = "DEBUG",
    appName,
    packageName,
    versionName = "1.0.0",
    versionCode = 1,
    files
  }) {
    const normalizedBuildType = String(buildType || "DEBUG").toUpperCase();
    if (normalizedBuildType !== "DEBUG" && normalizedBuildType !== "RELEASE") {
      throw new BuildValidationError("buildType must be 'DEBUG' or 'RELEASE'.");
    }

    if (normalizedBuildType === "RELEASE") {
      const ksPath = (env.RELEASE_KEYSTORE_PATH || "").trim();
      const ksPass = (env.RELEASE_KEYSTORE_PASS || "").trim();
      if (!ksPath || !ksPass) {
        throw new BuildValidationError(
          "RELEASE builds require server signing configuration (RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASS). Use DEBUG build or configure release keystore on build server."
        );
      }
    }

    validateProjectMetadata({ appName, packageName, versionName, versionCode });
    const sanitizedFiles = inspectBuildFiles(files);

    const jobId = `job_${crypto.randomUUID().replace(/-/g, "").slice(0, 16)}`;
    const now = Date.now();
    const job = {
      jobId,
      userId,
      projectId,
      buildType: normalizedBuildType,
      appName: appName.trim(),
      packageName,
      versionName,
      versionCode: Number(versionCode),
      status: "QUEUED",
      logs: [`[${new Date(now).toISOString()}] Job ${jobId} queued (${normalizedBuildType}).`],
      errorSummary: "",
      createdAtEpochMs: now,
      startedAtEpochMs: null,
      finishedAtEpochMs: null,
      durationMs: 0,
      artifact: null,
      _sanitizedFiles: sanitizedFiles
    };

    jobs.set(jobId, job);
    pendingQueue.push(jobId);
    pumpQueue();
    return serializeJob(job);
  }

  function pumpQueue() {
    while (activeCount < maxConcurrentBuilds && pendingQueue.length > 0) {
      const nextJobId = pendingQueue.shift();
      const job = jobs.get(nextJobId);
      if (!job) continue;
      activeCount += 1;
      executeJob(job).finally(() => {
        activeCount -= 1;
        pumpQueue();
      });
    }
  }

  async function executeJob(job) {
    const startedAt = Date.now();
    job.startedAtEpochMs = startedAt;
    const workDir = await fs.mkdtemp(path.join(os.tmpdir(), `myraa-build-${job.jobId}-`));

    try {
      job.status = "PREPARING_WORKSPACE";
      appendBoundedLog(job, `Prepared isolated workspace: ${workDir}`);

      if (!(await dirExists(sdkRoot))) {
        throw new Error(`Android SDK not found on build server at '${sdkRoot}'.`);
      }

      for (const file of job._sanitizedFiles) {
        const { resolvedTarget } = resolveInsideWorkspace(workDir, file.path);
        await fs.mkdir(path.dirname(resolvedTarget), { recursive: true });
        await fs.writeFile(resolvedTarget, file.content, "utf8");
      }

      // Write clean server-controlled local.properties
      await fs.writeFile(
        path.join(workDir, "local.properties"),
        `sdk.dir=${sdkRoot.replace(/\\/g, "/")}\n`,
        "utf8"
      );

      // Build sanitized child environment — strip ALL external AI keys & host secrets
      const sanitizedEnv = {
        PATH: env.PATH || "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        HOME: env.HOME || os.homedir(),
        JAVA_HOME: env.JAVA_HOME || "",
        ANDROID_SDK_ROOT: sdkRoot,
        ANDROID_HOME: sdkRoot,
        GRADLE_USER_HOME: env.GRADLE_USER_HOME || path.join(os.homedir(), ".gradle")
      };

      job.status = "RUNNING_GRADLE";
      const gradleTask = job.buildType === "RELEASE" ? ":app:assembleRelease" : ":app:assembleDebug";
      const gradleArgs = ["--no-daemon", "--console=plain", gradleTask];
      if (gradleOffline) {
        gradleArgs.unshift("--offline");
      }

      appendBoundedLog(job, `Executing: ${gradleBin} ${gradleArgs.join(" ")}`);
      const result = await runSubprocess({
        command: gradleBin,
        args: gradleArgs,
        cwd: workDir,
        env: sanitizedEnv,
        timeoutMs: buildTimeoutMs,
        onLog: (line) => appendBoundedLog(job, line)
      });

      if (result.timedOut) {
        throw new Error(`Build timed out after ${Math.round(buildTimeoutMs / 1000)}s.`);
      }
      if (result.exitCode !== 0) {
        throw new Error(`Gradle failed with exit code ${result.exitCode}. Inspect build log for details.`);
      }

      job.status = "VALIDATING_APK";
      appendBoundedLog(job, "Inspecting generated APK outputs...");
      const apkDir = path.join(workDir, "app", "build", "outputs", "apk");
      const candidates = await findApkFiles(apkDir);
      const preferred =
        candidates.find((p) => p.endsWith("-debug.apk") || p.endsWith("-release.apk")) || candidates[0];

      if (!preferred) {
        throw new Error("Gradle completed without producing an .apk artifact.");
      }

      const { sizeBytes, sha256, hasDex } = await verifyZipMagicAndSha256(preferred);
      appendBoundedLog(
        job,
        `Verified APK structure: bytes=${sizeBytes}, sha256=${sha256}, hasDex=${hasDex}`
      );

      let signatureVerified = false;
      if (await fileExists(apksignerBin)) {
        const sigRes = await runSubprocess({
          command: apksignerBin,
          args: ["verify", "--print-certs", preferred],
          cwd: workDir,
          env: sanitizedEnv,
          timeoutMs: 15_000,
          onLog: (line) => appendBoundedLog(job, `[apksigner] ${line}`)
        });
        signatureVerified = sigRes.exitCode === 0;
        if (!signatureVerified && job.buildType === "RELEASE") {
          throw new Error("Release APK failed apksigner verification.");
        }
      }

      await fs.mkdir(artifactDir, { recursive: true });
      const safeFileSlug = job.appName
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "") || "myraa-app";
      const fileName = `${safeFileSlug}-${job.buildType.toLowerCase()}-${job.versionName}.apk`;
      const storedApkPath = path.join(artifactDir, `${job.jobId}.apk`);
      await fs.copyFile(preferred, storedApkPath);

      const finishedAt = Date.now();
      job.finishedAtEpochMs = finishedAt;
      job.durationMs = Math.max(1, finishedAt - startedAt);
      job.artifact = {
        jobId: job.jobId,
        fileName,
        storedPath: storedApkPath,
        downloadPath: `/v1/artifacts/${job.jobId}/apk`,
        sha256,
        sizeBytes,
        buildType: job.buildType,
        apkVerified: true,
        signatureVerified,
        expiresAtEpochMs: finishedAt + artifactTtlMs
      };
      job.status = "SUCCEEDED";
      appendBoundedLog(job, `BUILD SUCCEEDED in ${job.durationMs}ms. Artifact ready: ${fileName}`);
    } catch (err) {
      const finishedAt = Date.now();
      job.finishedAtEpochMs = finishedAt;
      job.durationMs = Math.max(1, finishedAt - startedAt);
      job.status = "FAILED";
      job.errorSummary = err.message || "Unknown build failure.";
      appendBoundedLog(job, `BUILD FAILED: ${job.errorSummary}`);
    } finally {
      delete job._sanitizedFiles;
      await fs.rm(workDir, { recursive: true, force: true }).catch(() => {});
    }
  }

  function getJob(jobId) {
    const job = jobs.get(jobId);
    return job ? serializeJob(job) : null;
  }

  function getArtifactForDownload(jobId) {
    const job = jobs.get(jobId);
    if (!job || job.status !== "SUCCEEDED" || !job.artifact) return null;
    return {
      userId: job.userId,
      ...job.artifact
    };
  }

  function serializeJob(job) {
    return {
      jobId: job.jobId,
      userId: job.userId,
      projectId: job.projectId,
      buildType: job.buildType,
      appName: job.appName,
      packageName: job.packageName,
      versionName: job.versionName,
      versionCode: job.versionCode,
      status: job.status,
      logs: job.logs.join("\n"),
      errorSummary: job.errorSummary,
      createdAtEpochMs: job.createdAtEpochMs,
      durationMs: job.durationMs,
      artifact: job.artifact
        ? {
            jobId: job.artifact.jobId,
            fileName: job.artifact.fileName,
            downloadPath: job.artifact.downloadPath,
            sha256: job.artifact.sha256,
            sizeBytes: job.artifact.sizeBytes,
            buildType: job.artifact.buildType,
            apkVerified: job.artifact.apkVerified,
            signatureVerified: job.artifact.signatureVerified,
            expiresAtEpochMs: job.artifact.expiresAtEpochMs
          }
        : null
    };
  }

  return {
    enqueueBuild,
    getJob,
    getArtifactForDownload
  };
}
