import { initializeApp, type FirebaseOptions } from 'firebase/app'
import { connectAuthEmulator, getAuth, type Auth } from 'firebase/auth'
import {
  CACHE_SIZE_UNLIMITED, connectFirestoreEmulator, initializeFirestore, persistentLocalCache, persistentMultipleTabManager,
  type Firestore,
} from 'firebase/firestore'

export interface FirebaseHandles {
  auth: Auth
  db: Firestore
  emulator: boolean
}

const EMULATOR = import.meta.env.VITE_USE_EMULATOR === 'true'

// Firebase Hosting serves the project's public web config at this address,
// so no keys live in the repository.
async function hostingConfig(): Promise<FirebaseOptions> {
  const res = await fetch('/__/firebase/init.json')
  if (!res.ok) throw new Error('This page must be opened from its Firebase Hosting address.')
  // authDomain stays the project's firebaseapp.com address: it is the sign-in
  // redirect Google's OAuth client accepts out of the box.
  return res.json()
}

async function init(): Promise<FirebaseHandles> {
  const config: FirebaseOptions = EMULATOR
    ? { apiKey: 'demo-key', authDomain: 'demo-electropos.firebaseapp.com', projectId: 'demo-electropos', appId: 'demo-app' }
    : await hostingConfig()
  const app = initializeApp(config)
  const auth = getAuth(app)
  // The browser keeps a full copy of the shop, so the app opens instantly. It must
  // never be trimmed: the listeners only ask for documents newer than the newest one
  // cached (docs/data-model.md, "Reading").
  const db = initializeFirestore(app, {
    localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager(), cacheSizeBytes: CACHE_SIZE_UNLIMITED }),
  })
  if (EMULATOR) {
    connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true })
    connectFirestoreEmulator(db, '127.0.0.1', 8085)
  }
  return { auth, db, emulator: EMULATOR }
}

let handles: Promise<FirebaseHandles> | null = null

/** Initialises Firebase once, however many times it is asked for. */
export function initFirebase(): Promise<FirebaseHandles> {
  handles ??= init()
  return handles
}
