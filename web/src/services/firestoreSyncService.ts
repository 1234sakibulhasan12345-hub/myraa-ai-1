import {
  addDoc,
  collection,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc
} from "firebase/firestore";
import type {
  BrainLanguage,
  ChatMessage,
  ConversationProject,
  FirestoreLanguage,
  RoutingPolicy,
  UserWorkspaceProfile
} from "../types/myraa";
import { ensureFirebaseInitialized } from "./firebaseClient";

export function toFirestoreLanguage(lang: BrainLanguage): FirestoreLanguage {
  if (lang === "EN" || lang === "BN" || lang === "BN_EN" || lang === "AUTO") {
    return lang;
  }
  return "AUTO";
}

export async function ensureUserWorkspaceInFirestore(
  userId: string,
  displayName: string,
  defaultBackendUrl: string
): Promise<UserWorkspaceProfile | null> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId) return null;
  const userRef = doc(db, "users", userId);
  const snap = await getDoc(userRef);
  if (snap.exists()) {
    const data = snap.data() as Partial<UserWorkspaceProfile>;
    return {
      userId,
      displayName: data.displayName || displayName || "Commander",
      preferredLanguage: (data.preferredLanguage as FirestoreLanguage) || "AUTO",
      routingPolicy: (data.routingPolicy as RoutingPolicy) || "AUTO_BALANCED",
      activeProjectId: data.activeProjectId ?? null,
      backendServiceUrl: data.backendServiceUrl ?? defaultBackendUrl,
      preferredBuildType: data.preferredBuildType ?? "DEBUG",
      defaultPackagePrefix: data.defaultPackagePrefix ?? "com.myraa.app"
    };
  }

  const safeName = (displayName || "Commander").trim().slice(0, 100) || "Commander";
  const initialPayload = {
    userId,
    displayName: safeName,
    preferredLanguage: "AUTO",
    routingPolicy: "AUTO_BALANCED",
    backendServiceUrl: defaultBackendUrl.slice(0, 512),
    preferredBuildType: "DEBUG",
    defaultPackagePrefix: "com.myraa.app",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };
  await setDoc(userRef, initialPayload);
  return {
    userId,
    displayName: safeName,
    preferredLanguage: "AUTO",
    routingPolicy: "AUTO_BALANCED",
    backendServiceUrl: defaultBackendUrl,
    preferredBuildType: "DEBUG",
    defaultPackagePrefix: "com.myraa.app"
  };
}

export async function updateWorkspaceSettingsInFirestore(
  userId: string,
  updates: {
    displayName?: string;
    preferredLanguage?: FirestoreLanguage;
    routingPolicy?: RoutingPolicy;
    activeProjectId?: string;
    backendServiceUrl?: string;
  }
): Promise<void> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId) return;
  const userRef = doc(db, "users", userId);
  const payload: Record<string, unknown> = {
    updatedAt: serverTimestamp()
  };
  if (updates.displayName !== undefined) payload.displayName = updates.displayName.slice(0, 100);
  if (updates.preferredLanguage !== undefined) payload.preferredLanguage = updates.preferredLanguage;
  if (updates.routingPolicy !== undefined) payload.routingPolicy = updates.routingPolicy;
  if (updates.activeProjectId !== undefined) payload.activeProjectId = updates.activeProjectId;
  if (updates.backendServiceUrl !== undefined) {
    payload.backendServiceUrl = updates.backendServiceUrl.trim().replace(/\/+$/, "").slice(0, 512);
  }
  await updateDoc(userRef, payload);
}

export function subscribeProjectsInFirestore(
  userId: string,
  onProjects: (projects: ConversationProject[]) => void,
  onError?: (err: Error) => void
): () => void {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId) {
    onProjects([]);
    return () => {};
  }
  const colRef = collection(db, "users", userId, "projects");
  const q = query(colRef, orderBy("updatedAt", "desc"));
  return onSnapshot(
    q,
    (snapshot) => {
      const items: ConversationProject[] = snapshot.docs.map((d) => {
        const data = d.data();
        const updatedAt = data.updatedAt?.toMillis ? data.updatedAt.toMillis() : Date.now();
        return {
          id: d.id,
          userId: String(data.userId || userId),
          name: String(data.name || "Untitled Session"),
          targetFramework:
            (data.targetFramework as ConversationProject["targetFramework"]) || "WEB_APP",
          requirementsSummary: String(data.requirementsSummary || ""),
          detectedLanguage: (data.detectedLanguage as FirestoreLanguage) || "AUTO",
          architectureSummary: String(data.architectureSummary || ""),
          status: (data.status as ConversationProject["status"]) || "ACTIVE",
          updatedAtMs: updatedAt,
          syncedToFirestore: true
        };
      });
      onProjects(items);
    },
    (err) => {
      onError?.(err);
    }
  );
}

export async function createConversationProjectInFirestore(
  userId: string,
  name: string,
  language: BrainLanguage
): Promise<string | null> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId) return null;
  const colRef = collection(db, "users", userId, "projects");
  const docRef = await addDoc(colRef, {
    userId,
    name: (name || "MYRAA Web Chat").trim().slice(0, 120),
    targetFramework: "WEB_APP",
    requirementsSummary: "Interactive MYRAA Web Intelligence session",
    detectedLanguage: toFirestoreLanguage(language),
    architectureSummary: "Synced across MYRAA Web & Android",
    completedFeatures: [],
    pendingFeatures: [],
    status: "ACTIVE",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });
  return docRef.id;
}

export async function archiveProjectInFirestore(
  userId: string,
  projectId: string
): Promise<void> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId || !projectId) return;
  const ref = doc(db, "users", userId, "projects", projectId);
  await updateDoc(ref, {
    status: "ARCHIVED",
    updatedAt: serverTimestamp()
  });
}

export async function fetchProjectMessagesFromFirestore(
  userId: string,
  projectId: string
): Promise<ChatMessage[]> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId || !projectId) return [];
  const colRef = collection(db, "users", userId, "projects", projectId, "messages");
  const q = query(colRef, orderBy("createdAt", "asc"));
  const snap = await getDocs(q);
  return snap.docs.map((d) => {
    const data = d.data();
    return {
      id: d.id,
      role: data.role === "USER" ? "user" : "assistant",
      content: String(data.content || ""),
      providerId: String(data.selectedProviderId || "groq"),
      modelId: String(data.selectedModelId || ""),
      language: (data.detectedLanguage as BrainLanguage) || "AUTO",
      latencyMs: typeof data.latencyMs === "number" ? data.latencyMs : undefined,
      createdAtMs: data.createdAt?.toMillis ? data.createdAt.toMillis() : Date.now()
    };
  });
}

export async function saveMessageToFirestore(
  userId: string,
  projectId: string,
  msg: ChatMessage
): Promise<void> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId || !projectId) return;
  const colRef = collection(db, "users", userId, "projects", projectId, "messages");
  await addDoc(colRef, {
    userId,
    projectId,
    role: msg.role === "user" ? "USER" : "BRAIN",
    content: (msg.content || " ").slice(0, 16000),
    detectedLanguage: toFirestoreLanguage(msg.language),
    selectedProviderId: (msg.providerId || "myraa_web").slice(0, 64),
    selectedModelId: (msg.modelId || "auto").slice(0, 64),
    intentSummary: msg.role === "user" ? "Web prompt" : "Web response",
    assignedAgents: [],
    subtasks: [],
    latencyMs: Math.min(600000, Math.max(0, Math.round(msg.latencyMs || 0))),
    createdAt: serverTimestamp()
  });
}

export async function recordUsageLogToFirestore(
  userId: string,
  projectId: string,
  entry: {
    providerId: string;
    modelId: string;
    language: BrainLanguage;
    inputChars: number;
    outputChars: number;
    latencyMs: number;
    succeeded: boolean;
    promptTokens?: number;
    completionTokens?: number;
  }
): Promise<void> {
  const { db } = ensureFirebaseInitialized();
  if (!db || !userId || !projectId) return;
  const colRef = collection(db, "users", userId, "usage_logs");
  await addDoc(colRef, {
    userId,
    projectId,
    providerId: (entry.providerId || "unknown").slice(0, 64),
    modelId: (entry.modelId || "unknown").slice(0, 64),
    taskRole: "WEB_CHAT",
    inputLanguage: toFirestoreLanguage(entry.language),
    estimatedInputChars: Math.max(0, entry.inputChars),
    estimatedOutputChars: Math.max(0, entry.outputChars),
    latencyMs: Math.min(600000, Math.max(0, Math.round(entry.latencyMs))),
    succeeded: entry.succeeded,
    promptTokens: Math.max(0, Math.round(entry.promptTokens || 0)),
    completionTokens: Math.max(0, Math.round(entry.completionTokens || 0)),
    createdAt: serverTimestamp()
  });
}
