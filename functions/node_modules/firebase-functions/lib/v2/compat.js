const require_v2_providers_pubsub = require('./providers/pubsub.js');

//#region src/v2/compat.ts
const V1_COMPAT_PATCHED = Symbol.for("firebase.functions.v2.compat");
/**
* Patches a CloudEvent with V1 compatibility properties (context and message) if it's a supported type (e.g., Pub/Sub).
* This function ensures idempotency by using a Symbol to mark already patched events.
* @param event The CloudEvent to potentially patch.
* @returns The patched CloudEvent with V1 compatibility properties, or the original event if not a supported event type or already patched.
*/
function patchV1Compat(event) {
	if (event[V1_COMPAT_PATCHED]) {
		return event;
	}
	Object.defineProperty(event, V1_COMPAT_PATCHED, {
		value: true,
		enumerable: false,
		writable: false,
		configurable: false
	});
	switch (event.type) {
		case "google.cloud.pubsub.topic.v1.messagePublished": {
			const pubsubEvent = event;
			const pubsubData = pubsubEvent.data;
			if (!pubsubData || !pubsubData.message) {
				throw new Error("Malformed Pub/Sub event: missing 'message' property.");
			}
			if (!(pubsubData.message instanceof require_v2_providers_pubsub.Message)) {
				pubsubData.message = new require_v2_providers_pubsub.Message(pubsubData.message);
			}
			const v2Message = pubsubData.message;
			Object.defineProperty(pubsubEvent, "context", {
				get: () => {
					const service = "pubsub.googleapis.com";
					const sourcePrefix = `//${service}/`;
					return {
						eventId: v2Message.messageId,
						timestamp: v2Message.publishTime,
						eventType: "google.pubsub.topic.publish",
						resource: {
							service,
							name: event.source?.startsWith(sourcePrefix) ? event.source.substring(sourcePrefix.length) : event.source || ""
						},
						params: {}
					};
				},
				configurable: true,
				enumerable: true
			});
			Object.defineProperty(pubsubEvent, "message", {
				get: () => {
					const baseV1Message = {
						data: v2Message.data,
						messageId: v2Message.messageId,
						publishTime: v2Message.publishTime,
						attributes: v2Message.attributes,
						...v2Message.orderingKey && { orderingKey: v2Message.orderingKey }
					};
					return {
						...baseV1Message,
						get json() {
							return v2Message.json;
						},
						toJSON: () => baseV1Message
					};
				},
				configurable: true,
				enumerable: true
			});
			return pubsubEvent;
		}
		default: return event;
	}
}

//#endregion
exports.patchV1Compat = patchV1Compat;