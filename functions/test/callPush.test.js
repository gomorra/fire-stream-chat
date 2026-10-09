// `npm test` in functions/ runs this with Node's built-in test runner.

const assert = require("node:assert/strict");
const { describe, test } = require("node:test");
const {
    RING_TTL_MS,
    isMissedCall,
    unreadUpdates,
    callMessagePushData,
    incomingCallAndroidConfig,
} = require("../callPush");

const call = (content, duration) => ({ type: "CALL", content, duration });
const INCREMENT = { increment: 1 };

describe("isMissedCall", () => {
    test("a call that never connected and was not declined is missed", () => {
        for (const reason of ["hangup", "remote_hangup", "timeout", "error"]) {
            assert.equal(isMissedCall(call(reason, 0)), true, reason);
        }
    });

    test("a call message without a duration never connected", () => {
        assert.equal(isMissedCall({ type: "CALL", content: "hangup" }), true);
    });

    test("a declined call is not missed", () => {
        assert.equal(isMissedCall(call("declined", 0)), false);
    });

    test("a call that connected is not missed, however it ended", () => {
        assert.equal(isMissedCall(call("hangup", 42)), false);
        assert.equal(isMissedCall(call("error", 7)), false);
    });

    test("only a call message can be a missed call", () => {
        assert.equal(isMissedCall({ type: "TEXT", content: "hangup" }), false);
    });
});

describe("unreadUpdates", () => {
    test("a message raises every recipient's unread count", () => {
        assert.deepEqual(unreadUpdates({ type: "TEXT" }, ["a", "b"], INCREMENT), {
            "unreadCounts.a": INCREMENT,
            "unreadCounts.b": INCREMENT,
        });
    });

    test("a missed call raises the callee's unread count", () => {
        assert.deepEqual(unreadUpdates(call("timeout", 0), ["callee"], INCREMENT), {
            "unreadCounts.callee": INCREMENT,
        });
    });

    test("a call the callee took or declined leaves the unread count alone", () => {
        assert.deepEqual(unreadUpdates(call("hangup", 65), ["callee"], INCREMENT), {});
        assert.deepEqual(unreadUpdates(call("declined", 0), ["callee"], INCREMENT), {});
    });
});

describe("callMessagePushData", () => {
    test("a call message carries its duration as a string", () => {
        assert.deepEqual(callMessagePushData(call("hangup", 65)), { callDurationSeconds: "65" });
        assert.deepEqual(callMessagePushData({ type: "CALL", content: "timeout" }), { callDurationSeconds: "0" });
    });

    test("other messages carry nothing extra", () => {
        assert.deepEqual(callMessagePushData({ type: "TEXT", content: "hi" }), {});
    });
});

describe("incomingCallAndroidConfig", () => {
    test("rings at high priority and expires with the ring", () => {
        assert.deepEqual(incomingCallAndroidConfig(), { priority: "high", ttl: RING_TTL_MS });
        assert.equal(RING_TTL_MS, 30_000);
    });
});
