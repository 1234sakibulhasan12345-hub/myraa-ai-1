import {
  ConfirmationResult,
  createUserWithEmailAndPassword,
  GoogleAuthProvider,
  onAuthStateChanged,
  RecaptchaVerifier,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signInWithPhoneNumber,
  signInWithPopup,
  signInWithRedirect,
  signOut,
  updateProfile,
  type User
} from "firebase/auth";
import type { AuthenticatedUserView } from "../types/myraa";
import { ensureFirebaseInitialized } from "./firebaseClient";

export function mapFirebaseUser(user: User | null): AuthenticatedUserView | null {
  if (!user) return null;
  return {
    uid: user.uid,
    displayName: user.displayName || user.email?.split("@")[0] || user.phoneNumber || "Commander",
    email: user.email || "",
    phoneNumber: user.phoneNumber || "",
    photoURL: user.photoURL || "",
    emailVerified: Boolean(user.emailVerified),
    providerIds: user.providerData.map((p) => p.providerId).filter(Boolean)
  };
}

export function formatFirebaseAuthError(err: unknown): string {
  const code =
    typeof err === "object" && err !== null && "code" in err
      ? String((err as { code?: unknown }).code || "")
      : "";
  const msg = err instanceof Error ? err.message : "Authentication request failed.";
  switch (code) {
    case "auth/operation-not-allowed":
      return "This sign-in method is not yet enabled in Firebase Console (Authentication -> Sign-in method) for project gen-lang-client-0645829429.";
    case "auth/popup-blocked":
      return "Popup blocked by browser. Switching to redirect sign-in or allow popups for this site.";
    case "auth/popup-closed-by-user":
      return "Google Sign-In popup was closed before completing.";
    case "auth/unauthorized-domain":
      return "This website domain is not listed under Firebase Console -> Authentication -> Settings -> Authorized domains.";
    case "auth/invalid-credential":
    case "auth/wrong-password":
    case "auth/user-not-found":
      return "Invalid email or password. Please verify your credentials.";
    case "auth/email-already-in-use":
      return "An account already exists with this email. Sign in instead.";
    case "auth/weak-password":
      return "Password must be at least 6 characters long.";
    case "auth/invalid-phone-number":
      return "Enter a valid phone number with country code (e.g., +8801XXXXXXXXX or +1XXXXXXXXXX).";
    case "auth/invalid-verification-code":
      return "The SMS verification code is invalid or expired.";
    case "auth/too-many-requests":
      return "Too many sign-in attempts. Please wait a moment and try again.";
    default:
      return msg;
  }
}

export function subscribeAuthState(
  onUser: (user: AuthenticatedUserView | null) => void
): () => void {
  const { auth } = ensureFirebaseInitialized();
  if (!auth) {
    onUser(null);
    return () => {};
  }
  return onAuthStateChanged(auth, (fbUser) => {
    onUser(mapFirebaseUser(fbUser));
  });
}

export async function signInWithGoogleWeb(): Promise<AuthenticatedUserView | null> {
  const { auth } = ensureFirebaseInitialized();
  if (!auth) throw new Error("Firebase Web App configuration is incomplete.");
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  try {
    const cred = await signInWithPopup(auth, provider);
    return mapFirebaseUser(cred.user);
  } catch (err) {
    const code =
      typeof err === "object" && err !== null && "code" in err
        ? String((err as { code?: unknown }).code || "")
        : "";
    if (code === "auth/popup-blocked") {
      await signInWithRedirect(auth, provider);
      return null;
    }
    throw new Error(formatFirebaseAuthError(err));
  }
}

export async function signInWithEmailWeb(
  email: string,
  password: string
): Promise<AuthenticatedUserView> {
  const { auth } = ensureFirebaseInitialized();
  if (!auth) throw new Error("Firebase Web App configuration is incomplete.");
  try {
    const cred = await signInWithEmailAndPassword(auth, email.trim(), password);
    const mapped = mapFirebaseUser(cred.user);
    if (!mapped) throw new Error("Sign-in succeeded without a user record.");
    return mapped;
  } catch (err) {
    throw new Error(formatFirebaseAuthError(err));
  }
}

export async function registerWithEmailWeb(
  displayName: string,
  email: string,
  password: string
): Promise<AuthenticatedUserView> {
  const { auth } = ensureFirebaseInitialized();
  if (!auth) throw new Error("Firebase Web App configuration is incomplete.");
  try {
    const cred = await createUserWithEmailAndPassword(auth, email.trim(), password);
    const cleanName = displayName.trim();
    if (cleanName) {
      await updateProfile(cred.user, { displayName: cleanName.slice(0, 80) });
    }
    const mapped = mapFirebaseUser(cred.user);
    if (!mapped) throw new Error("Account created without a user record.");
    return { ...mapped, displayName: cleanName || mapped.displayName };
  } catch (err) {
    throw new Error(formatFirebaseAuthError(err));
  }
}

export async function sendPasswordResetWeb(email: string): Promise<void> {
  const { auth } = ensureFirebaseInitialized();
  if (!auth) throw new Error("Firebase Web App configuration is incomplete.");
  try {
    await sendPasswordResetEmail(auth, email.trim());
  } catch (err) {
    throw new Error(formatFirebaseAuthError(err));
  }
}

let activeRecaptchaVerifier: RecaptchaVerifier | null = null;

export async function startPhoneOtpSignIn(
  phoneNumber: string,
  recaptchaContainerId: string
): Promise<ConfirmationResult> {
  const { auth } = ensureFirebaseInitialized();
  if (!auth) throw new Error("Firebase Web App configuration is incomplete.");
  try {
    if (activeRecaptchaVerifier) {
      activeRecaptchaVerifier.clear();
      activeRecaptchaVerifier = null;
    }
    activeRecaptchaVerifier = new RecaptchaVerifier(auth, recaptchaContainerId, {
      size: "invisible"
    });
    return await signInWithPhoneNumber(auth, phoneNumber.trim(), activeRecaptchaVerifier);
  } catch (err) {
    if (activeRecaptchaVerifier) {
      activeRecaptchaVerifier.clear();
      activeRecaptchaVerifier = null;
    }
    throw new Error(formatFirebaseAuthError(err));
  }
}

export async function confirmPhoneOtpCode(
  confirmation: ConfirmationResult,
  code: string
): Promise<AuthenticatedUserView> {
  try {
    const cred = await confirmation.confirm(code.trim());
    const mapped = mapFirebaseUser(cred.user);
    if (!mapped) throw new Error("Phone sign-in succeeded without a user record.");
    return mapped;
  } catch (err) {
    throw new Error(formatFirebaseAuthError(err));
  }
}

export async function updateUserDisplayNameWeb(displayName: string): Promise<AuthenticatedUserView> {
  const { auth } = ensureFirebaseInitialized();
  if (!auth || !auth.currentUser) {
    throw new Error("Sign in before updating your profile.");
  }
  const clean = displayName.trim();
  if (!clean) throw new Error("Display name cannot be empty.");
  await updateProfile(auth.currentUser, { displayName: clean.slice(0, 80) });
  const mapped = mapFirebaseUser(auth.currentUser);
  if (!mapped) throw new Error("Profile update failed.");
  return mapped;
}

export async function signOutWeb(): Promise<void> {
  const { auth } = ensureFirebaseInitialized();
  if (auth) {
    await signOut(auth);
  }
}
