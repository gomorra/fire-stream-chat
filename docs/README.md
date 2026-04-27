# FireStream Chat — Docs Index

One-line pointers to every doc in this folder. Start here when you're not sure where a topic lives.

| Doc | Purpose |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Clean-architecture overview, tech stack, feature flows, navigation, package layout. |
| [SPEC.md](SPEC.md) | Product-level feature list — what the app does, from a user's perspective. |
| [ROADMAP.md](ROADMAP.md) | Phased plan from current state toward Signal/WhatsApp-parity messaging. |
| [PATTERNS.md](PATTERNS.md) | Grep-able catalogue of named codebase conventions (slice ownership, AppError, fakes vs. mocks, etc.). Cited by `AGENT-NOTE` headers. |
| [FEATURE-MAP.md](FEATURE-MAP.md) | Cross-cutting feature → file lookup table for features spanning 4+ packages. Check before grepping. |
| [DOMAIN-MODELS.md](DOMAIN-MODELS.md) | Shapes of the framework-free Kotlin data classes in `domain/model/`. |
| [SCHEMA-ROOM.md](SCHEMA-ROOM.md) | Local Room schema — entities, columns, both `AppDatabase` and `SignalDatabase`. |
| [SCHEMA-FIRESTORE.md](SCHEMA-FIRESTORE.md) | Firestore collections and Realtime Database paths — the authoritative remote store. |
| [CLOUD-FUNCTIONS.md](CLOUD-FUNCTIONS.md) | The three Firebase Cloud Functions in `functions/index.js` and their triggers. |
| [test-plans/](test-plans/) | Manual / sprint test plans, dated by sprint. |
