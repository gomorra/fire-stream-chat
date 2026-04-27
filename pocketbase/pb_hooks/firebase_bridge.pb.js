/// <reference path="../pb_data/types.d.ts" />

// POST /api/auth/firebase-bridge
//
// Exchanges a Firebase ID token (from Firebase Phone OTP on the device) for a
// PocketBase session token. The Android client calls this once after Phone OTP
// completes; subsequent requests authenticate with the returned PB token.
//
// Verification path (no firebase-admin / no googleapis — we run in Goja):
//   1. Fetch Google's x509 RSA public keys (cached in this hook's module scope
//      for 6 h, cleared if the JWT's kid is unknown).
//   2. Try $security.parseJWT(token, key) against each key — exactly one will
//      verify the RS256 signature; failures throw and we move on.
//   3. Manually validate iss / aud / exp / iat against FIREBASE_PROJECT_ID.
//   4. Find-or-create the matching `users` record by firebase_uid; mint a PB
//      session via $tokens.recordAuthToken.
//
// All caches and module state survive across requests because pb_hooks files
// share one Goja runtime per file. They reset on `pocketbase serve` restart.

const X509_URL =
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
const KEY_CACHE_TTL_MS = 6 * 60 * 60 * 1000
const IAT_SKEW_SEC = 60
const RANDOM_PASSWORD_LEN = 32

let cachedKeys = null
let cachedAt = 0

function fetchGooglePublicKeys(forceRefresh) {
  const now = Date.now()
  if (!forceRefresh && cachedKeys && now - cachedAt < KEY_CACHE_TTL_MS) {
    return cachedKeys
  }
  const res = $http.send({
    url: X509_URL,
    method: "GET",
    timeout: 10,
  })
  if (res.statusCode !== 200) {
    throw new Error("google x509 fetch failed: status " + res.statusCode)
  }
  // res.json is the parsed body; PB returns it as a plain object.
  const keys = res.json
  if (!keys || typeof keys !== "object") {
    throw new Error("google x509 response was not an object")
  }
  cachedKeys = keys
  cachedAt = now
  return keys
}

// Try each currently-valid Google key. Exactly one will match the JWT's
// signing kid; the rest throw inside parseJWT. Cheaper than decoding the
// header to read kid directly (which would need a base64url helper Goja
// doesn't ship).
function tryKeys(token, keys) {
  let lastErr = null
  for (const kid in keys) {
    try {
      return $security.parseJWT(token, keys[kid])
    } catch (e) {
      lastErr = e
    }
  }
  throw new Error("signature verification failed: " + (lastErr || "no key matched"))
}

function verifyFirebaseIdToken(token, projectId) {
  let claims
  try {
    claims = tryKeys(token, fetchGooglePublicKeys(false))
  } catch (e) {
    // Maybe Google rotated; clear the cache and refetch once.
    claims = tryKeys(token, fetchGooglePublicKeys(true))
  }

  const now = Math.floor(Date.now() / 1000)
  const expectedIss = "https://securetoken.google.com/" + projectId
  if (claims.iss !== expectedIss) throw new Error("bad iss: " + claims.iss)
  if (claims.aud !== projectId) throw new Error("bad aud: " + claims.aud)
  if (!claims.exp || claims.exp <= now) throw new Error("token expired")
  if (claims.iat && claims.iat > now + IAT_SKEW_SEC) throw new Error("iat in future")
  // Firebase always sets auth_time when this came from a real sign-in; reject
  // tokens with a future auth_time (clock-skew bound matches iat).
  if (claims.auth_time && claims.auth_time > now + IAT_SKEW_SEC) {
    throw new Error("auth_time in future")
  }
  if (!claims.sub) throw new Error("missing sub")
  // Firebase puts the uid in user_id (and also sub). When both are present they
  // must agree — disagreeing values would mean the token was forged across two
  // identities (the verified signature alone can't catch this).
  if (claims.user_id && claims.sub && claims.user_id !== claims.sub) {
    throw new Error("sub / user_id mismatch")
  }
  if (!claims.user_id) claims.user_id = claims.sub
  return claims
}

function findOrCreateUser(claims) {
  const dao = $app.dao()
  // Existing user — by firebase_uid.
  try {
    return dao.findFirstRecordByFilter(
      "users",
      "firebase_uid = {:uid}",
      { uid: claims.user_id }
    )
  } catch (e) {
    // Not found — fall through to create.
  }
  const usersCollection = dao.findCollectionByNameOrId("users")
  const record = new Record(usersCollection)
  record.set("firebase_uid", claims.user_id)
  record.set("phone", claims.phone_number || "")
  // Auth collections require a password even when we never use it. Random,
  // discarded, and unreachable: pb_schema.json gates the users collection
  // with allowEmailAuth/allowOAuth2Auth/allowUsernameAuth all `false`, so the
  // /api/collections/users/auth-with-* endpoints can never reach this hash.
  // If a future schema edit flips any of those flags, the random 32-char
  // password is still computationally infeasible to brute force, but the
  // attack surface widens — keep the schema gate intact.
  record.setPassword($security.randomString(RANDOM_PASSWORD_LEN))
  dao.saveRecord(record)
  return record
}

function filteredRecord(record) {
  return {
    id: record.id,
    firebase_uid: record.get("firebase_uid"),
    phone: record.get("phone"),
    name: record.get("name"),
    avatar_url: record.get("avatar_url"),
    status_text: record.get("status_text"),
  }
}

routerAdd("POST", "/api/auth/firebase-bridge", (c) => {
  const projectId = $os.getenv("FIREBASE_PROJECT_ID")
  if (!projectId) {
    return c.json(500, {
      code: 500,
      message: "FIREBASE_PROJECT_ID env var is not set on the server",
      data: {},
    })
  }

  let body
  try {
    body = $apis.requestInfo(c).data || {}
  } catch (e) {
    return c.json(400, { code: 400, message: "invalid request body", data: {} })
  }
  const idToken = body.idToken
  if (!idToken || typeof idToken !== "string") {
    return c.json(400, { code: 400, message: "missing idToken", data: {} })
  }

  let claims
  try {
    claims = verifyFirebaseIdToken(idToken, projectId)
  } catch (e) {
    return c.json(401, {
      code: 401,
      message: "invalid id token: " + e,
      data: {},
    })
  }

  let userRecord
  try {
    userRecord = findOrCreateUser(claims)
  } catch (e) {
    return c.json(500, {
      code: 500,
      message: "failed to upsert user: " + e,
      data: {},
    })
  }

  let token
  try {
    token = $tokens.recordAuthToken($app, userRecord)
  } catch (e) {
    return c.json(500, {
      code: 500,
      message: "failed to mint session token: " + e,
      data: {},
    })
  }

  return c.json(200, {
    token: token,
    record: filteredRecord(userRecord),
  })
})
