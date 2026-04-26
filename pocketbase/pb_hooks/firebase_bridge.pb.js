/// <reference path="../pb_data/types.d.ts" />

// POST /api/auth/firebase-bridge
//
// Exchanges a Firebase ID token (from Firebase Phone OTP on the device) for a
// PocketBase session token. The Android client calls this once after Phone OTP
// completes; subsequent requests authenticate with the returned PB token.
//
// Real implementation lands in step 5. This stub returns 501 so the wiring can
// be smoke-tested end-to-end before the JWT-verification work.
routerAdd("POST", "/api/auth/firebase-bridge", (c) => {
  return c.json(501, {
    code: 501,
    message: "firebase-bridge not yet implemented (plan step 5)",
    data: {},
  })
})
