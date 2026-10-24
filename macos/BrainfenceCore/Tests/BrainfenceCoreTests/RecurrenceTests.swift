import XCTest
@testable import BrainfenceCore

/// Port of android/…/RecurrenceEngineTest.kt and TimeGatePhaseTest.kt.
final class RecurrenceTests: XCTestCase {
    private let zone = TimeZone(identifier: "America/New_York")!
    private lazy var clock = ZonedClock(timeZone: zone)

    private func instant(_ date: String, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        clock.instant(CivilDate(date)!, hour: hour, minute: minute)
    }

    private func status(
        _ type: String?, _ config: String = "{}",
        availableFrom: String? = nil, dueAt: String? = nil,
        last: Date? = nil, now: Date
    ) -> OccurrenceStatus {
        computeOccurrenceStatus(
            recurrenceType: type, recurrenceConfig: config,
            availableFrom: availableFrom, dueAt: dueAt,
            lastCompletionAt: last, now: now, timeZone: zone
        )
    }

    private func upcomingDate(_ s: OccurrenceStatus, file: StaticString = #filePath, line: UInt = #line) -> CivilDate? {
        guard case .upcoming(let d) = s else {
            XCTFail("expected upcoming, got \(s)", file: file, line: line)
            return nil
        }
        return clock.date(of: d)
    }

    // MARK: One-off

    func testOneOffNoCompletionIsDue() {
        XCTAssertEqual(status(nil, now: instant("2026-04-16")), .due)
    }

    func testOneOffCompletedIsCompleted() {
        XCTAssertEqual(status(nil, last: instant("2026-04-15"), now: instant("2026-04-16")), .completed)
    }

    // MARK: Daily

    func testDailyNotCompletedTodayIsDue() {
        XCTAssertEqual(status("daily", last: instant("2026-04-15"), now: instant("2026-04-16")), .due)
    }

    func testDailyCompletedTodayIsCompleted() {
        XCTAssertEqual(status("daily", last: instant("2026-04-16", 8), now: instant("2026-04-16", 14)), .completed)
    }

    func testDailyNeverCompletedIsDue() {
        XCTAssertEqual(status("daily", now: instant("2026-04-16")), .due)
    }

    func testDailySpecificDaysTodayAllowed() {
        // 2026-04-16 is a Thursday
        XCTAssertEqual(status("daily", #"{"days": ["mon", "thu", "fri"]}"#, now: instant("2026-04-16")), .due)
    }

    func testDailySpecificDaysTodayNotAllowed() {
        // 2026-04-15 is a Wednesday → next allowed is Thursday
        let s = status("daily", #"{"days": ["mon", "thu", "fri"]}"#, now: instant("2026-04-15"))
        XCTAssertEqual(upcomingDate(s), CivilDate("2026-04-16"))
    }

    func testDailyNoDaysKeyIsEveryDay() {
        XCTAssertEqual(status("daily", now: instant("2026-04-16")), .due)
    }

    func testCompletionJustBeforeLocalMidnightCountsForThatDay() {
        // 23:30 New York is already the next day in UTC.
        XCTAssertEqual(status("daily", last: instant("2026-04-15", 23, 30), now: instant("2026-04-16", 8)), .due)
        XCTAssertEqual(status("daily", last: instant("2026-04-15", 23, 30), now: instant("2026-04-15", 23, 45)), .completed)
    }

    // MARK: Weekly

    func testWeeklyTargetDayNotCompleted() {
        XCTAssertEqual(status("weekly", #"{"day": "thu"}"#, now: instant("2026-04-16")), .due)
    }

    func testWeeklyTargetDayCompleted() {
        XCTAssertEqual(status("weekly", #"{"day": "thu"}"#, last: instant("2026-04-16", 8), now: instant("2026-04-16", 14)), .completed)
    }

    func testWeeklyNotTargetDay() {
        let s = status("weekly", #"{"day": "thu"}"#, now: instant("2026-04-15"))
        XCTAssertEqual(upcomingDate(s), CivilDate("2026-04-16"))
    }

    func testWeeklyAfterTargetDayRollsToNextWeek() {
        let s = status("weekly", #"{"day": "mon"}"#, now: instant("2026-04-16"))
        XCTAssertEqual(upcomingDate(s), CivilDate("2026-04-20"))
    }

    // MARK: Monthly

    func testMonthlyTargetDay() {
        // 2nd Wednesday of April 2026 = April 8
        XCTAssertEqual(status("monthly", #"{"week": 2, "day": "wed"}"#, now: instant("2026-04-08")), .due)
    }

    func testMonthlyBeforeTargetDay() {
        let s = status("monthly", #"{"week": 2, "day": "wed"}"#, now: instant("2026-04-06"))
        XCTAssertEqual(upcomingDate(s), CivilDate("2026-04-08"))
    }

    func testMonthlyAfterTargetDay() {
        // 2nd Wednesday of May 2026 = May 13
        let s = status("monthly", #"{"week": 2, "day": "wed"}"#, now: instant("2026-04-16"))
        XCTAssertEqual(upcomingDate(s), CivilDate("2026-05-13"))
    }

    // MARK: Interval

    func testIntervalNeverCompletedIsDue() {
        XCTAssertEqual(status("interval", #"{"minutes": 480}"#, now: instant("2026-04-16")), .due)
    }

    func testIntervalElapsed() {
        XCTAssertEqual(status("interval", #"{"minutes": 480}"#, last: instant("2026-04-16", 0), now: instant("2026-04-16", 9)), .due)
    }

    func testIntervalNotElapsed() {
        let s = status("interval", #"{"minutes": 480}"#, last: instant("2026-04-16", 6), now: instant("2026-04-16", 10))
        XCTAssertEqual(s, .upcoming(instant("2026-04-16", 14)))
    }

    func testIntervalOutsideActiveDays() {
        let config = #"{"minutes": 60, "active_days": ["mon", "tue", "wed", "thu", "fri"], "active_start": "09:00", "active_end": "17:00"}"#
        XCTAssertEqual(status("interval", config, now: instant("2026-04-18", 20)), .notDue) // Saturday
    }

    func testIntervalOutsideActiveHours() {
        let config = #"{"minutes": 60, "active_start": "09:00", "active_end": "17:00"}"#
        XCTAssertEqual(status("interval", config, now: instant("2026-04-16", 20)), .notDue)
    }

    func testIntervalInsideActiveWindow() {
        let config = #"{"minutes": 60, "active_days": ["mon", "tue", "wed", "thu", "fri"], "active_start": "09:00", "active_end": "17:00"}"#
        XCTAssertEqual(status("interval", config, now: instant("2026-04-16", 12)), .due)
    }

    // MARK: Availability window

    func testBeforeAvailableFromIsNotDue() {
        XCTAssertEqual(status("daily", availableFrom: "07:00", dueAt: "10:00", now: instant("2026-04-16", 6)), .notDue)
    }

    func testInsideWindowIsDue() {
        XCTAssertEqual(status("daily", availableFrom: "07:00", dueAt: "10:00", now: instant("2026-04-16", 8)), .due)
    }

    func testAfterDueAtIsStillCompletable() {
        XCTAssertEqual(status("daily", availableFrom: "07:00", dueAt: "10:00", now: instant("2026-04-16", 15)), .due)
    }

    func testInsideWindowCompletedToday() {
        XCTAssertEqual(status("daily", availableFrom: "07:00", dueAt: "10:00", last: instant("2026-04-16", 8), now: instant("2026-04-16", 9)), .completed)
    }

    // MARK: Edge cases

    func testUnknownRecurrenceTypeIsNotDue() {
        XCTAssertEqual(status("unknown_future_type", now: instant("2026-04-16")), .notDue)
    }

    func testMalformedConfigDoesNotCrash() {
        XCTAssertEqual(status("weekly", "not json", now: instant("2026-04-16")), .notDue)
        XCTAssertEqual(status("daily", "not json", now: instant("2026-04-16")), .due)
    }

    // MARK: Next occurrence

    func testNextOccurrenceDaily() {
        let next = computeNextOccurrence(recurrenceType: "daily", recurrenceConfig: "{}", lastCompletionAt: nil, now: instant("2026-04-16"), timeZone: zone)
        XCTAssertEqual(next, clock.startOfDay(CivilDate("2026-04-17")!))
    }

    func testNextOccurrenceDailySpecificDays() {
        let next = computeNextOccurrence(recurrenceType: "daily", recurrenceConfig: #"{"days": ["mon"]}"#, lastCompletionAt: nil, now: instant("2026-04-16"), timeZone: zone)
        XCTAssertEqual(next, clock.startOfDay(CivilDate("2026-04-20")!))
    }

    func testNextOccurrenceOneOffIsNil() {
        XCTAssertNil(computeNextOccurrence(recurrenceType: nil, recurrenceConfig: "{}", lastCompletionAt: nil, now: instant("2026-04-16"), timeZone: zone))
    }

    // MARK: Time gate phase (TimeGatePhaseTest.kt)

    private func phase(_ from: String?, _ due: String?, _ hour: Int, _ minute: Int = 0) -> TimeGatePhase? {
        computeTaskPhase(availableFrom: from, dueAt: due, now: instant("2026-04-16", hour, minute), timeZone: zone)
    }

    func testPhases() {
        XCTAssertEqual(phase("07:00", "10:00", 6, 59), .beforeStart)
        XCTAssertEqual(phase("07:00", "10:00", 7, 0), .active)
        XCTAssertEqual(phase("07:00", "10:00", 8, 30), .active)
        XCTAssertEqual(phase("07:00", "10:00", 10, 0), .pastDue)
        XCTAssertEqual(phase("07:00", "10:00", 15, 0), .pastDue)
        XCTAssertNil(phase(nil, nil, 12))
        XCTAssertEqual(phase("07:00", nil, 6), .beforeStart)
        XCTAssertEqual(phase("07:00", nil, 8), .active)
        XCTAssertEqual(phase(nil, "10:00", 8), .active)
        XCTAssertEqual(phase(nil, "10:00", 12), .pastDue)
    }

    func testSingleDigitHourParses() {
        XCTAssertEqual(phase("9:00", nil, 8), .beforeStart)
        XCTAssertEqual(phase("9:00", nil, 9), .active)
    }

    func testMalformedTimesAreIgnored() {
        XCTAssertNil(phase("", "garbage", 12))
        XCTAssertNil(phase("24:00", "7:5", 12))
    }
}

final class CivilTimeTests: XCTestCase {
    func testEpochDayRoundTrip() {
        XCTAssertEqual(CivilDate("1970-01-01")!.epochDay, 0)
        XCTAssertEqual(CivilDate("2026-04-16")!.epochDay, 20559)
        for day in stride(from: -1000, through: 30000, by: 37) {
            XCTAssertEqual(CivilDate(epochDay: day).epochDay, day)
        }
    }

    func testDayOfWeek() {
        XCTAssertEqual(CivilDate("1970-01-01")!.dayOfWeek, .thursday)
        XCTAssertEqual(CivilDate("2026-04-16")!.dayOfWeek, .thursday)
        XCTAssertEqual(CivilDate("2026-04-19")!.dayOfWeek, .sunday)
        XCTAssertEqual(CivilDate("2026-04-20")!.dayOfWeek, .monday)
    }

    func testPlusMonthsClamps() {
        XCTAssertEqual(CivilDate("2026-01-31")!.plusMonths(1), CivilDate("2026-02-28"))
        XCTAssertEqual(CivilDate("2026-12-15")!.plusMonths(1), CivilDate("2027-01-15"))
    }

    func testTimestampParsing() {
        let expected = Date(timeIntervalSince1970: 1_776_348_000) // 2026-04-16T14:00:00Z
        XCTAssertEqual(Timestamp.parse("2026-04-16T14:00:00Z"), expected)
        XCTAssertEqual(Timestamp.parse("2026-04-16T14:00:00.000Z"), expected)
        XCTAssertEqual(Timestamp.parse("2026-04-16 14:00:00+00"), expected)
        XCTAssertEqual(Timestamp.parse("2026-04-16T10:00:00-04:00"), expected)
        XCTAssertEqual(Timestamp.parse("2026-04-16 14:00:00"), expected)
        XCTAssertEqual(Timestamp.parse("2026-04-16T14:00:00.123456+00:00")!.timeIntervalSince1970,
                       expected.timeIntervalSince1970 + 0.123456, accuracy: 0.000001)
        XCTAssertNil(Timestamp.parse("yesterday"))
        XCTAssertNil(Timestamp.parse(nil))
    }

    func testTimestampFormatRoundTrips() {
        let d = Date(timeIntervalSince1970: 1_776_348_000.25)
        XCTAssertEqual(Timestamp.format(d), "2026-04-16T14:00:00.250Z")
        XCTAssertEqual(Timestamp.parse(Timestamp.format(d)), d)
    }
}
