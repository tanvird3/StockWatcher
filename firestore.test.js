const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_tsx_user";
const BOB_UID = "bob_tsx_user";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validNow() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or write watchlist", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("watchlist").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("watchlist")
      .doc("RY_TO")
      .set({
        userId: ALICE_UID,
        symbol: "RY.TO",
        name: "Royal Bank of Canada",
        exchange: "TSX",
        createdAt: validNow(),
        updatedAt: validNow(),
      })
  );
});

test("Owner can create, list, update, and delete watchlist item", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("watchlist")
    .doc("RY_TO");

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      symbol: "RY.TO",
      name: "Royal Bank of Canada",
      exchange: "TSX",
      sector: "Financials",
      notes: "Core Canadian bank holding",
      createdAt: validNow(),
      updatedAt: validNow(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watchlist").get()
  );

  await assertSucceeds(
    docRef.update({
      notes: "Strong dividend growth",
      updatedAt: validNow(),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("Cross-user access to watchlist and alerts is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("alerts")
      .doc("alert_1")
      .set({
        userId: ALICE_UID,
        symbol: "SHOP.TO",
        stockName: "Shopify Inc.",
        conditionType: "ABOVE",
        targetValue: 150.0,
        isActive: true,
        triggeredCount: 0,
        createdAt: validNow(),
        updatedAt: validNow(),
      })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("alerts").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("alerts")
      .doc("alert_1")
      .delete()
  );
});

test("Shadow update with ghost field or immutable field mutation is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("alerts")
    .doc("alert_2");

  await assertFails(
    docRef.set({
      userId: ALICE_UID,
      symbol: "ENB.TO",
      stockName: "Enbridge Inc.",
      conditionType: "BELOW",
      targetValue: 50.0,
      isActive: true,
      triggeredCount: 0,
      ghostAdmin: true,
      createdAt: validNow(),
      updatedAt: validNow(),
    })
  );

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      symbol: "ENB.TO",
      stockName: "Enbridge Inc.",
      conditionType: "BELOW",
      targetValue: 50.0,
      isActive: true,
      triggeredCount: 0,
      createdAt: validNow(),
      updatedAt: validNow(),
    })
  );

  await assertFails(
    docRef.update({
      symbol: "TD.TO",
      updatedAt: validNow(),
    })
  );
});

test("Invalid alert conditionType, future timestamp, or invalid doc ID is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("alerts")
      .doc("alert_bad_cond")
      .set({
        userId: ALICE_UID,
        symbol: "TD.TO",
        stockName: "TD Bank",
        conditionType: "INVALID_OP",
        targetValue: 80.0,
        isActive: true,
        triggeredCount: 0,
        createdAt: validNow(),
        updatedAt: validNow(),
      })
  );

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("alerts")
      .doc("alert_future")
      .set({
        userId: ALICE_UID,
        symbol: "TD.TO",
        stockName: "TD Bank",
        conditionType: "ABOVE",
        targetValue: 80.0,
        isActive: true,
        triggeredCount: 0,
        createdAt: new Date(Date.now() + 60000),
        updatedAt: validNow(),
      })
  );

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("watchlist")
      .doc("bad.id.with.dots")
      .set({
        userId: ALICE_UID,
        symbol: "TD.TO",
        name: "TD Bank",
        exchange: "TSX",
        createdAt: validNow(),
        updatedAt: validNow(),
      })
  );
});

test("Owner can create and acknowledge alertEvents", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const eventRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("alertEvents")
    .doc("evt_1");

  await assertSucceeds(
    eventRef.set({
      userId: ALICE_UID,
      alertId: "alert_1",
      symbol: "SHOP.TO",
      conditionType: "ABOVE",
      triggerPrice: 152.35,
      targetValue: 150.0,
      message: "SHOP.TO crossed above CAD 150.00 (now 152.35)",
      acknowledged: false,
      createdAt: validNow(),
    })
  );

  await assertSucceeds(
    eventRef.update({
      acknowledged: true,
      updatedAt: validNow(),
    })
  );
});
