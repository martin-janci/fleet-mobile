import XCTest

/// Does a `claudefleet:` URL actually reach this app on iOS, and does anything
/// draw when it does?
///
/// Two claims, both unproven until now, and both only answerable from outside
/// the app's process:
///
///  - **Delivery.** `ContentView` has an `onOpenURL` that calls
///    `onPairLink`, and `Info.plist` declares the scheme. A source scan
///    (`PairLinkWiringTest`) checks both strings are present. On Android the
///    equivalent scan passed for months against a manifest that routed the
///    second link nowhere, because `launchMode` was missing — the wiring was
///    right and the platform still did not deliver. Nothing says iOS is
///    different; nothing had asked.
///  - **Rendering.** No Compose has ever been drawn on iOS. The shared suite
///    runs on Kotlin/Native and the app links and launches, but every screen
///    in this app has only ever been drawn on Android.
///
/// A unit-test bundle cannot answer either: it is loaded *into* the app, so it
/// can call the handler directly, which proves the handler works and says
/// nothing about whether the system ever calls it. `XCUIDevice.system.open`
/// goes through the OS, exactly as `xcrun simctl openurl` does.
final class PairLinkUITests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    /// Port 1 on loopback refuses instantly, so a debug build's auto-submit
    /// fails fast instead of waiting on a network that is not there.
    private let pairURL = URL(string: "claudefleet:http://127.0.0.1:1/pair#ABCDEFGH")!

    /// The app launches, and this records what its accessibility tree looks
    /// like.
    ///
    /// The attachment is the point. Compose Multiplatform bridges its
    /// semantics to UIKit accessibility, but how a Compose `Text` or
    /// `OutlinedTextField` surfaces to `XCUIElementQuery` is not something
    /// this repository has ever observed — and there is no way to observe it
    /// here, because there is no Mac. Guessing at `staticTexts` versus
    /// `textFields` and being wrong costs a CI round and teaches nothing;
    /// attaching the tree costs the same round and answers the question.
    func testTheAppLaunchesAndItsAccessibilityTreeIsRecorded() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(
            app.wait(for: .runningForeground, timeout: 30),
            "the app did not reach the foreground"
        )

        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "accessibility-tree-at-launch"
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// The whole chain: a URL goes through the OS, the Pair screen comes back
    /// holding the code.
    ///
    /// `XCUIDevice.shared.system.open` is the same delivery path a person
    /// tapping a link uses. The assertion is deliberately loose about *which*
    /// element carries the text — `descendants(matching: .any)` rather than
    /// `staticTexts` — because what matters is that the eight characters are
    /// on screen at all, and the first run's attachment is what will tell us
    /// how to tighten it.
    func testADeepLinkFillsThePairScreen() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))

        XCUIDevice.shared.system.open(pairURL)

        let code = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "ABCDEFGH", "ABCDEFGH"))
            .firstMatch

        let arrived = code.waitForExistence(timeout: 20)

        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = arrived ? "accessibility-tree-after-link" : "accessibility-tree-when-code-not-found"
        tree.lifetime = .keepAlways
        add(tree)

        XCTAssertTrue(
            arrived,
            "the pairing code never appeared after opening \(pairURL). Either iOS did not "
                + "deliver the URL, or Compose drew it somewhere XCUITest cannot see it — the "
                + "attached accessibility tree says which."
        )
    }
}
