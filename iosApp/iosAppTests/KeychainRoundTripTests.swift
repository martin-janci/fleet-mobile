import XCTest
import Shared

/// The iOS Keychain round trip — the one piece of this app that a machine could
/// check and, until now, never did.
///
/// `KeychainSecretsTest` in `shared/src/iosTest` executes the same class on the
/// same simulator and can only ever see it **refuse**: the Kotlin/Native test
/// binary is launched with `simctl spawn`, so it is not an installed app, holds
/// no `keychain-access-group` entitlement, and `securityd` answers
/// `errSecNotAvailable` (-25291) to everything. That is a property of the test
/// host, not of the store, and no amount of care in `Secrets.ios.kt` changes it.
///
/// This bundle is hosted by `iosApp`, so it is loaded into the app's process and
/// runs under the app's bundle identifier and entitlements. That is the whole
/// reason it exists, and it is the same difference that lets Android's
/// instrumentation test do the round trip: an instrumentation test *is*
/// installed as an app.
///
/// Every case uses its own account and deletes it afterwards — a Keychain item
/// outlives the process that wrote it, so a leftover would make the next run
/// read a value it did not write. `AndroidSecretsTest` follows the same rule for
/// the same reason.
final class KeychainRoundTripTests: XCTestCase {

    private let service = "dev.claudefleet.mobile.xctest"
    private var accounts: [String] = []

    private func store(_ name: String) -> KeychainSecrets {
        let account = "\(name)-\(UUID().uuidString)"
        accounts.append(account)
        return KeychainSecrets(service: service, account: account)
    }

    override func tearDown() async throws {
        for account in accounts {
            try? await KeychainSecrets(service: service, account: account).clear()
        }
        accounts = []
        try await super.tearDown()
    }

    private func credential(
        hub: String = "https://fleet.example.com",
        token: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        name: String = "phone",
        mode: String = "full"
    ) -> Credentials {
        Credentials(hub: hub, token: token, name: name, mode: mode)
    }

    /// The claim the whole file exists for.
    func testAWrittenCredentialComesBack() async throws {
        let secrets = store("round-trip")
        let written = credential()

        try await secrets.write(credentials: written)
        let read = try await secrets.read()

        // Hoisted out of the assertion: XCTest's macros take autoclosures, and
        // an `await` inside one is a compile error ("'async' call in an
        // autoclosure that does not support concurrency"). Every assertion in
        // this file reads its value first for that reason.
        let got = try XCTUnwrap(read, "the item was written, so it must read back")
        XCTAssertEqual(got.hub, written.hub)
        XCTAssertEqual(got.token, written.token)
        XCTAssertEqual(got.name, written.name)
        XCTAssertEqual(got.mode, written.mode)
    }

    /// An account with nothing under it is not an error and is not a credential.
    func testAnEmptyStoreReadsAsUnpaired() async throws {
        let read = try await store("empty").read()

        XCTAssertNil(read, "nothing was ever written under this account")
    }

    /// `write` replaces rather than accumulating.
    ///
    /// The implementation deletes and then adds rather than branching on whether
    /// an item exists, precisely so there is no path where a failed update leaves
    /// the *old* token in place. This is that claim: re-pairing against a
    /// different hub reads back the new credential and not the first.
    func testWritingTwiceKeepsOnlyTheSecond() async throws {
        let secrets = store("replace")
        try await secrets.write(credentials: credential())

        let replacement = credential(
            hub: "https://other.example.com",
            token: "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            name: "kiosk",
            mode: "readonly"
        )
        try await secrets.write(credentials: replacement)

        let stored = try await secrets.read()
        let read = try XCTUnwrap(stored)
        XCTAssertEqual(read.token, replacement.token, "the second write must replace the first")
        XCTAssertEqual(read.mode, "readonly")
    }

    /// `clear` removes the item rather than reporting success and leaving it.
    ///
    /// The contract `Secrets.clear` states, and the reason it throws rather than
    /// failing quietly: `AppSession.forget()` publishes `Unpaired` on the
    /// strength of this returning, so a silent no-op would tell someone who has
    /// just lost their phone that the credential is gone while it is still on
    /// the device.
    func testClearingReallyRemovesTheItem() async throws {
        let secrets = store("clear")
        try await secrets.write(credentials: credential())
        let before = try await secrets.read()
        XCTAssertNotNil(before)

        try await secrets.clear()

        let after = try await secrets.read()
        XCTAssertNil(after, "clear() must remove the item, not merely claim to")
    }

    /// `errSecItemNotFound` is benign: there was nothing to delete.
    func testClearingAnEmptyStoreIsNotAnError() async throws {
        try await store("clear-empty").clear()
    }

    /// Two accounts under one service do not share an item.
    ///
    /// Really a check that the account is part of the item's identity rather
    /// than decoration: if it were dropped from the query, every store in the
    /// process would be the same store.
    func testTwoAccountsHoldSeparateItems() async throws {
        let first = store("a")
        let second = store("b")
        try await first.write(credentials: credential())

        let other = try await second.read()
        let mine = try await first.read()
        XCTAssertNil(other, "a different account must not see the first one's item")
        XCTAssertNotNil(mine)
    }

    /// A credential whose text is not ASCII survives the C boundary.
    ///
    /// The value crosses as UTF-8 bytes — `encodeToByteArray` out,
    /// `readBytes(...).decodeToString()` back — and a length taken in the wrong
    /// units is the classic way that breaks, invisibly, for everyone whose
    /// client name happens to be ASCII.
    func testACredentialWithNonAsciiTextSurvives() async throws {
        let secrets = store("utf8")
        let awkward = credential(name: "Martin's iPhone — kuchyňa 🛰")

        try await secrets.write(credentials: awkward)

        let stored = try await secrets.read()
        let read = try XCTUnwrap(stored)
        XCTAssertEqual(read.name, awkward.name, "the name crosses a C boundary as UTF-8")
    }
}
