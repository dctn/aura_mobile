import Foundation
import XCTest
@testable import AuraKit

/// A file in App/Resources, found from this file's own path.
private func appResource(_ name: String, file: String = #filePath) throws -> Data {
    let url = URL(fileURLWithPath: file)
        .deletingLastPathComponent()
        .appendingPathComponent("../../../../App/Resources/\(name)")
        .standardized
    return try Data(contentsOf: url)
}

final class ModesCatalogTests: XCTestCase {
    private func loadModes() throws -> [Mode] {
        return try JSONDecoder().decode([Mode].self, from: appResource("modes.json"))
    }

    func testHas65Modes() throws {
        XCTAssertEqual(try loadModes().count, 65)
    }

    func testIdsAndWordsAreUnique() throws {
        let modes = try loadModes()
        XCTAssertEqual(Set(modes.map { $0.id }).count, modes.count)
        XCTAssertEqual(Set(modes.map { $0.word }).count, modes.count)
    }

    func testEveryShelfHasModes() throws {
        let modes = try loadModes()
        for shelf in Shelf.allCases {
            XCTAssertFalse(modes.filter { $0.shelf == shelf }.isEmpty, "\(shelf.rawValue) is empty")
        }
    }

    func testEveryKindIsOneTheBackendTakes() throws {
        let allowed: Set<String> = ["focus", "meeting", "recovery", "walk"]
        for mode in try loadModes() {
            XCTAssertTrue(allowed.contains(mode.kind), "\(mode.id) has kind \(mode.kind)")
        }
    }

    func testBreatheModesHaveAPattern() throws {
        for mode in try loadModes() where mode.run == .breathe {
            XCTAssertNotNil(mode.pattern, "\(mode.id) has no pattern")
        }
    }

    func testGuidedModesHavePrompts() throws {
        for mode in try loadModes() where mode.run == .guided {
            XCTAssertFalse((mode.prompts ?? []).isEmpty, "\(mode.id) has no prompts")
        }
    }
}

final class AppConfigFileTests: XCTestCase {
    func testDevConfigDecodesAndNamesEveryService() throws {
        let config = try JSONDecoder().decode(AppConfig.self, from: appResource("aura-dev.json"))
        XCTAssertEqual(config.stage, "dev")
        XCTAssertEqual(config.region, "us-west-1")
        for service in ServiceName.allCases {
            XCTAssertNotNil(config.baseURL(service), "\(service.rawValue) has no address")
        }
    }

    func testLiveServicesTakesTheDevConfig() throws {
        XCTAssertNoThrow(try LiveServices.make(configData: appResource("aura-dev.json")))
    }

    func testLiveServicesRefusesProdAndMumbai() throws {
        var config = TestRig.config
        config.stage = "prod"
        XCTAssertThrowsError(try LiveServices.make(configData: JSONEncoder().encode(config)))
        config = TestRig.config
        config.region = "ap-south-1"
        XCTAssertThrowsError(try LiveServices.make(configData: JSONEncoder().encode(config)))
    }
}
