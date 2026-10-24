import Foundation

/// ISO day of week. `ordinal` matches java.time's `DayOfWeek.ordinal`
/// (MONDAY = 0 … SUNDAY = 6) so ported arithmetic stays identical.
public enum Weekday: Int, Sendable, CaseIterable, Comparable {
    case monday = 0, tuesday, wednesday, thursday, friday, saturday, sunday

    public var ordinal: Int { rawValue }

    /// Lowercase three-letter abbreviation used throughout the schema ("mon" … "sun").
    public var abbreviation: String {
        ["mon", "tue", "wed", "thu", "fri", "sat", "sun"][rawValue]
    }

    public init?(abbreviation: String) {
        guard let i = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
            .firstIndex(of: abbreviation.lowercased()) else { return nil }
        self.init(rawValue: i)
    }

    public static func < (a: Weekday, b: Weekday) -> Bool { a.rawValue < b.rawValue }
}

/// A calendar date with no time zone (java.time.LocalDate equivalent).
public struct CivilDate: Sendable, Hashable, Comparable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Parses "YYYY-MM-DD".
    public init?(_ iso: String) {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        self.init(year: parts[0], month: parts[1], day: parts[2])
    }

    /// Days since 1970-01-01 (java.time `toEpochDay`). Howard Hinnant's algorithm.
    public var epochDay: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    public init(epochDay: Int) {
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d)
    }

    public var dayOfWeek: Weekday {
        // 1970-01-01 was a Thursday (ordinal 3).
        let r = (epochDay + 3) % 7
        return Weekday(rawValue: r < 0 ? r + 7 : r)!
    }

    public func plusDays(_ n: Int) -> CivilDate { CivilDate(epochDay: epochDay + n) }

    /// java.time `plusMonths`: clamps the day to the target month's length.
    public func plusMonths(_ n: Int) -> CivilDate {
        let total = year * 12 + (month - 1) + n
        let y = Int((Double(total) / 12).rounded(.down))
        let m = total - y * 12 + 1
        return CivilDate(year: y, month: m, day: min(day, CivilDate.daysIn(year: y, month: m)))
    }

    /// `with(TemporalAdjusters.nextOrSame(day))`
    public func nextOrSame(_ target: Weekday) -> CivilDate {
        plusDays((target.ordinal - dayOfWeek.ordinal + 7) % 7)
    }

    /// `with(TemporalAdjusters.next(day))` — strictly after this date.
    public func next(_ target: Weekday) -> CivilDate {
        let delta = (target.ordinal - dayOfWeek.ordinal + 7) % 7
        return plusDays(delta == 0 ? 7 : delta)
    }

    /// `with(TemporalAdjusters.previousOrSame(day))`
    public func previousOrSame(_ target: Weekday) -> CivilDate {
        plusDays(-((dayOfWeek.ordinal - target.ordinal + 7) % 7))
    }

    public static func daysIn(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        default:
            let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            return leap ? 29 : 28
        }
    }

    public static func < (a: CivilDate, b: CivilDate) -> Bool { a.epochDay < b.epochDay }

    public var description: String { String(format: "%04d-%02d-%02d", year, month, day) }
}

/// Bridges `Date` ⇄ civil dates/times in a specific time zone.
public struct ZonedClock: Sendable {
    public let timeZone: TimeZone
    private let calendar: Calendar

    public init(timeZone: TimeZone) {
        self.timeZone = timeZone
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        self.calendar = cal
    }

    public func date(of instant: Date) -> CivilDate {
        let c = calendar.dateComponents([.year, .month, .day], from: instant)
        return CivilDate(year: c.year!, month: c.month!, day: c.day!)
    }

    /// Seconds elapsed since local midnight (java.time `toLocalTime()` as a number).
    public func secondsIntoDay(of instant: Date) -> Int {
        let c = calendar.dateComponents([.hour, .minute, .second], from: instant)
        return (c.hour ?? 0) * 3600 + (c.minute ?? 0) * 60 + (c.second ?? 0)
    }

    /// `atStartOfDay(zone).toInstant()`
    public func startOfDay(_ date: CivilDate) -> Date {
        calendar.date(from: DateComponents(year: date.year, month: date.month, day: date.day))!
    }

    /// The instant at a local date + time of day (used by tests and scheduling).
    public func instant(_ date: CivilDate, hour: Int, minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(
            year: date.year, month: date.month, day: date.day, hour: hour, minute: minute
        ))!
    }
}

/// A wall-clock time of day ("H:mm" / "HH:mm"), stored as minutes since midnight.
public struct TimeOfDay: Sendable, Hashable, Comparable {
    public let minutes: Int

    public init(hour: Int, minute: Int) { self.minutes = hour * 60 + minute }

    /// Accepts single- and double-digit hours ("9:00" and "09:00"), like
    /// Android's `parseTaskTime`. Returns nil for blank or malformed input.
    public init?(_ text: String?) {
        guard let text else { return nil }
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2,
              (1...2).contains(parts[0].count), parts[1].count == 2,
              parts[0].allSatisfy(\.isASCII), parts[1].allSatisfy(\.isASCII),
              let h = Int(parts[0]), let m = Int(parts[1]),
              (0...23).contains(h), (0...59).contains(m)
        else { return nil }
        self.init(hour: h, minute: m)
    }

    public var seconds: Int { minutes * 60 }
    public var hour: Int { minutes / 60 }
    public var minute: Int { minutes % 60 }

    public static func < (a: TimeOfDay, b: TimeOfDay) -> Bool { a.minutes < b.minutes }
}

/// Parsing/formatting of the timestamp strings that flow through PowerSync.
///
/// Values written locally use ISO-8601 with a `Z` suffix; values replicated
/// from Postgres `timestamptz` may use a space separator, 6-digit fractions
/// and `+00` style offsets. Both must parse.
public enum Timestamp {
    public static func parse(_ text: String?) -> Date? {
        guard let text = text?.trimmingCharacters(in: .whitespaces), text.count >= 16 else { return nil }
        let chars = Array(text)
        func int(_ r: Range<Int>) -> Int? {
            guard r.upperBound <= chars.count else { return nil }
            return Int(String(chars[r]))
        }
        guard let year = int(0..<4), chars[4] == "-", let month = int(5..<7), chars[7] == "-",
              let day = int(8..<10), chars[10] == "T" || chars[10] == " ",
              let hour = int(11..<13), chars[13] == ":", let minute = int(14..<16)
        else { return nil }

        var i = 16
        var second = 0
        var fraction = 0.0
        if i < chars.count, chars[i] == ":" {
            guard let s = int((i + 1)..<(i + 3)) else { return nil }
            second = s
            i += 3
            if i < chars.count, chars[i] == "." {
                var j = i + 1
                while j < chars.count, chars[j].isNumber { j += 1 }
                fraction = Double("0" + String(chars[i..<j])) ?? 0
                i = j
            }
        }

        var offsetSeconds = 0
        if i < chars.count {
            let sign = chars[i]
            if sign == "Z" || sign == "z" {
                i += 1
            } else if sign == "+" || sign == "-" {
                let digits = chars[(i + 1)...].filter(\.isNumber)
                guard digits.count == 2 || digits.count == 4,
                      let oh = Int(String(digits.prefix(2))) else { return nil }
                let om = digits.count == 4 ? Int(String(digits.suffix(2))) ?? 0 : 0
                offsetSeconds = (oh * 3600 + om * 60) * (sign == "-" ? -1 : 1)
                i = chars.count
            } else {
                return nil
            }
        }
        guard i == chars.count else { return nil }

        // No offset → UTC, matching SQLite's interpretation in date(…, 'localtime').
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let base = cal.date(from: DateComponents(
            year: year, month: month, day: day, hour: hour, minute: minute, second: second
        )) else { return nil }
        return base.addingTimeInterval(fraction - Double(offsetSeconds))
    }

    /// ISO-8601 UTC with milliseconds, e.g. "2026-04-16T14:03:00.123Z"
    /// (the shape Android writes via `Instant.toString()`).
    public static func format(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(secondsFromGMT: 0)
        return f.string(from: date)
    }
}
