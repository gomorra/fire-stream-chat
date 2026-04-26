/// <reference path="../pb_data/types.d.ts" />

// Marks `presence` rows offline when their `last_heartbeat` is older than the
// stale threshold. Replaces the Firebase `syncPresenceToFirestore` cloud
// function for the PocketBase backend (RTDB onDisconnect has no PB equivalent).
//
// Real implementation lands in step 6. This stub registers the cron at the
// 30-second cadence so the schedule shape is in place; the body is a no-op.
cronAdd("presence-sweep", "*/30 * * * * *", () => {
  // no-op (plan step 6)
})
