/// <reference path="../pb_data/types.d.ts" />

// Triggered after a new `messages` record is created. Mints an FCM HTTP v1
// access token via the service-account JWT-bearer flow, then pushes a data
// notification to each recipient's `fcm_token`.
//
// Real implementation lands in step 7. This stub is a no-op — it only logs the
// event so the hook registration is verifiable.
onRecordAfterCreateRequest((e) => {
  console.log("[push_on_message stub] message created:", e.record.id)
}, "messages")
