const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const SAMPLE_SHA256 = "a".repeat(64);

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp() {
  return new Date(Date.now() - 5000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validWorkspace(uid) {
  return {
    userId: uid,
    displayName: "Sakib",
    preferredLanguage: "AUTO",
    routingPolicy: "AUTO_BALANCED",
    createdAt: pastTimestamp(),
    updatedAt: pastTimestamp(),
  };
}

function validProject(uid, status = "PLANNING") {
  return {
    userId: uid,
    name: "Myraa Messaging App",
    targetFramework: "ANDROID_COMPOSE",
    requirementsSummary: "Build chat with Google Sign-In and AI assistant.",
    detectedLanguage: "EN",
    architectureSummary: "MVVM with Compose and Firebase",
    completedFeatures: ["Core Setup"],
    pendingFeatures: ["Chat UI"],
    status,
    createdAt: pastTimestamp(),
    updatedAt: pastTimestamp(),
  };
}

function validMessage(uid, projectId) {
  return {
    userId: uid,
    projectId,
    role: "USER",
    content: "বাংলায় একটা Android app প্ল্যান করো",
    detectedLanguage: "BN",
    selectedProviderId: "google_gemini",
    selectedModelId: "gemini-3.8-flash",
    intentSummary: "Plan Android application in Bengali",
    assignedAgents: ["Master Planner Agent", "Android App Developer Agent"],
    subtasks: ["Design architecture", "Create Compose screens"],
    latencyMs: 420,
    createdAt: pastTimestamp(),
  };
}

function validProjectFile(uid, projectId) {
  return {
    userId: uid,
    projectId,
    path: "app/src/main/java/com/example/MainActivity.kt",
    fileName: "MainActivity.kt",
    language: "kotlin",
    content: "package com.example\nclass MainActivity",
    sizeBytes: 39,
    checksumSha256: SAMPLE_SHA256,
    updatedByAgent: "Coding Agent",
    createdAt: pastTimestamp(),
    updatedAt: pastTimestamp(),
  };
}

function validProjectVersion(uid, projectId) {
  return {
    userId: uid,
    projectId,
    versionNumber: 1,
    summary: "Initial app scaffold",
    createdByAgent: "App Builder Studio",
    fileEntries: [
      {
        path: "app/src/main/java/com/example/MainActivity.kt",
        language: "kotlin",
        content: "package com.example\nclass MainActivity",
      },
    ],
    createdAt: pastTimestamp(),
  };
}

function validBuildJob(uid, projectId, status = "QUEUED") {
  return {
    userId: uid,
    projectId,
    status,
    buildType: "DEBUG",
    appName: "Myraa Chat",
    packageName: "com.myraa.chat",
    versionName: "1.0.0",
    versionCode: 1,
    targetServerUrl: "https://build.example.com",
    startedByModel: "gemini-3.8-flash",
    logSummary: "Enqueued build job",
    errorSummary: "",
    durationMs: 0,
    createdAt: pastTimestamp(),
    updatedAt: pastTimestamp(),
  };
}

function validArtifact(uid, projectId, jobId, verified = true, sizeBytes = 4194304) {
  return {
    userId: uid,
    projectId,
    jobId,
    fileName: "myraa-chat-debug.apk",
    downloadUrl: "https://build.example.com/v1/artifacts/job_1/apk",
    sha256: SAMPLE_SHA256,
    sizeBytes,
    buildType: "DEBUG",
    apkVerified: verified,
    expiresAtEpochMs: Date.now() + 86400000,
    createdAt: pastTimestamp(),
  };
}

function validUsageLog(uid, projectId) {
  return {
    userId: uid,
    projectId,
    providerId: "google_gemini",
    modelId: "gemini-3.8-flash",
    taskRole: "MASTER_PLANNING",
    inputLanguage: "BN_EN",
    estimatedInputChars: 42,
    estimatedOutputChars: 512,
    latencyMs: 650,
    succeeded: true,
    promptTokens: 12,
    completionTokens: 128,
    estimatedCostUsdMicros: 5,
    createdAt: pastTimestamp(),
  };
}

test("Unauthenticated caller cannot read or write any workspace or project", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("projects").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").set(validProject(ALICE_UID)));
});

test("User cannot list /users root collection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").get());
});

test("Owner can create and update their own workspace including backendServiceUrl, non-owner is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validWorkspace(ALICE_UID)));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ displayName: "Hacked", updatedAt: pastTimestamp() }));
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ isAdmin: true, updatedAt: pastTimestamp() }));

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({
    routingPolicy: "ULTRA_FAST",
    backendServiceUrl: "https://myraa-builder.example.com",
    updatedAt: pastTimestamp(),
  }));
});

test("Owner can create, list, update projects with app metadata; cross-user and archived updates fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").set(validProject(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").update({
    packageName: "com.myraa.chatapp",
    appCategory: "CHATTING",
    appVersionCode: 2,
    updatedAt: pastTimestamp(),
  }));

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_2").set(validProject(ALICE_UID)));
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_spoof").set(validProject(BOB_UID)));

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").update({
    status: "ARCHIVED",
    updatedAt: pastTimestamp(),
  }));
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").update({
    name: "Renamed after archive",
    updatedAt: pastTimestamp(),
  }));
});

test("Messages enforce Parent Gate and append-only immutability", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("missing_proj").collection("messages").doc("msg_1").set(validMessage(ALICE_UID, "missing_proj"))
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").set(validProject(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("messages").doc("msg_1").set(validMessage(ALICE_UID, "proj_1")));
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("messages").doc("msg_1").update({ content: "tampered" }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("messages").get());
});

test("Project files & immutable versions enforce path traversal prevention, parent gate, and owner isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("files").doc("f1").set(validProjectFile(ALICE_UID, "proj_1"))
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").set(validProject(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("files").doc("f1").set(validProjectFile(ALICE_UID, "proj_1")));

  // Path traversal attempt must fail
  const badFile = validProjectFile(ALICE_UID, "proj_1");
  badFile.path = "../../etc/passwd";
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("files").doc("f_bad").set(badFile));

  // Cross-user read denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("files").get());

  // Versions are immutable after creation
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("versions").doc("v1").set(validProjectVersion(ALICE_UID, "proj_1")));
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("versions").doc("v1").update({ summary: "tampered" }));
});

test("Build jobs enforce terminal state lock & verified-only APK artifacts enforce parent gates", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").set(validProject(ALICE_UID)));

  // Create queued build job and update to RUNNING_GRADLE, then SUCCEEDED
  const jobRef = aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("build_jobs").doc("job_1");
  await assertSucceeds(jobRef.set(validBuildJob(ALICE_UID, "proj_1", "QUEUED")));
  await assertSucceeds(jobRef.update({ status: "RUNNING_GRADLE", logSummary: "Running assembleDebug", updatedAt: pastTimestamp() }));
  await assertSucceeds(jobRef.update({ status: "SUCCEEDED", durationMs: 12000, updatedAt: pastTimestamp() }));

  // Terminal build job cannot be mutated afterward
  await assertFails(jobRef.update({ status: "FAILED", errorSummary: "tamper", updatedAt: pastTimestamp() }));

  // Unverified or missing-job artifact must fail
  const artRef = aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("artifacts");
  await assertFails(artRef.doc("art_fake").set(validArtifact(ALICE_UID, "proj_1", "job_1", false, 4194304)));
  await assertFails(artRef.doc("art_orphan").set(validArtifact(ALICE_UID, "proj_1", "missing_job", true, 4194304)));

  // Verified artifact linked to real build job succeeds & is append-only
  await assertSucceeds(artRef.doc("art_1").set(validArtifact(ALICE_UID, "proj_1", "job_1", true, 4194304)));
  await assertFails(artRef.doc("art_1").update({ sizeBytes: 123 }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").collection("artifacts").get());
});

test("Usage logs enforce owner isolation and extended token/cost bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("usage_logs").doc("log_1").set(validUsageLog(ALICE_UID, "proj_1")));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("usage_logs").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("usage_logs").get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("usage_logs").doc("log_1").update({ succeeded: false }));
});
