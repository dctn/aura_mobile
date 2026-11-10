import AuraKit
import Foundation
import HealthKit

/// Where last night's sleep comes from: Apple Health on a phone, a fixed night under UI tests.
protocol SleepSource: Sendable {
    /// False where there is no Health app (an iPad), so the check-in doesn't offer it.
    var isAvailable: Bool { get }
    /// Asks to read sleep. HealthKit never says whether reading was allowed: a refusal just reads no samples.
    func requestAccess() async throws
    /// The sleep samples that overlap `from`...`to`, any order.
    func samples(from: Date, to: Date) async throws -> [SleepSample]
}

/// Reads Apple Health's sleep analysis. It asks to read sleep only and never writes anything.
/// HKHealthStore is thread safe, so the class is marked Sendable by hand.
final class HealthKitSleepSource: SleepSource, @unchecked Sendable {
    private let store = HKHealthStore()
    private let sleepType = HKCategoryType(.sleepAnalysis)

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    func requestAccess() async throws {
        let share: Set<HKSampleType> = []
        let read: Set<HKObjectType> = [sleepType]
        try await store.requestAuthorization(toShare: share, read: read)
    }

    func samples(from start: Date, to end: Date) async throws -> [SleepSample] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
        let descriptor = HKSampleQueryDescriptor(
            predicates: [HKSamplePredicate<HKCategorySample>.categorySample(type: sleepType, predicate: predicate)],
            sortDescriptors: [SortDescriptor<HKCategorySample>(\.startDate, order: .forward)],
            limit: nil
        )
        let found: [HKCategorySample] = try await descriptor.result(for: store)
        return found.compactMap { Self.sample($0) }
    }

    /// Core, deep, REM and unspecified are asleep; in bed and awake are kept for the night's other numbers.
    private static func sample(_ sample: HKCategorySample) -> SleepSample? {
        guard let value = HKCategoryValueSleepAnalysis(rawValue: sample.value) else { return nil }
        let kind: SleepSample.Kind
        switch value {
        case .inBed: kind = .inBed
        case .asleepCore: kind = .asleepCore
        case .asleepDeep: kind = .asleepDeep
        case .asleepREM: kind = .asleepREM
        case .asleepUnspecified: kind = .asleepUnspecified
        case .awake: kind = .awake
        default: return nil
        }
        return SleepSample(start: sample.startDate, end: sample.endDate, kind: kind)
    }
}

/// UI tests: with `--uitesting-health-night` last night is 23:20 to 06:50 with a 10 minute wake, else there's nothing.
struct StubSleepSource: SleepSource {
    var hasNight = false

    var isAvailable: Bool { true }

    func requestAccess() async throws {}

    func samples(from: Date, to: Date) async throws -> [SleepSample] {
        guard hasNight else { return [] }
        return Self.night(wakingOn: Date())
    }

    /// The night before `day`: in bed 23:10 to 06:55, asleep 23:20 to 06:50, awake 03:00 to 03:10.
    static func night(wakingOn day: Date) -> [SleepSample] {
        let midnight = Calendar.current.startOfDay(for: day)
        // Minutes from midnight; negative is the evening before.
        let parts: [(Int, Int, SleepSample.Kind)] = [
            (-50, 415, .inBed),
            (-40, 30, .asleepCore),
            (30, 90, .asleepDeep),
            (90, 180, .asleepCore),
            (180, 190, .awake),
            (190, 270, .asleepREM),
            (270, 410, .asleepCore),
        ]
        return parts.map { part in
            SleepSample(start: midnight.addingTimeInterval(TimeInterval(part.0 * 60)),
                        end: midnight.addingTimeInterval(TimeInterval(part.1 * 60)),
                        kind: part.2)
        }
    }
}
