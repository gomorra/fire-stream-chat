/// <reference path="../pb_data/types.d.ts" />

// Marks `presence` rows offline when their `last_heartbeat` is older than the
// stale threshold. Replaces the Firebase `syncPresenceToFirestore` cloud
// function for the PocketBase backend (RTDB onDisconnect has no PB equivalent).
//
// Cadence: every 30s; row is considered stale after 60s of silence. The
// matching client-side freshness window in PocketBasePresenceSource.derivePresence
// is also 60s, so even if this cron lags briefly the UI won't show a stale
// "online" indicator.
cronAdd("presence-sweep", "*/30 * * * * *", () => {
  const STALE_AFTER_MS = 60_000
  const cutoff = Date.now() - STALE_AFTER_MS

  const stale = $app.findRecordsByFilter(
    "presence",
    "is_online = true && last_heartbeat < {:cutoff}",
    "",   // sort
    100,  // limit
    0,    // offset
    { cutoff: cutoff }
  )

  if (!stale || stale.length === 0) return

  for (const record of stale) {
    record.set("is_online", false)
    try {
      $app.save(record)
    } catch (e) {
      console.log(`[presence-sweep] save failed for ${record.id}: ${e}`)
    }
  }
})
