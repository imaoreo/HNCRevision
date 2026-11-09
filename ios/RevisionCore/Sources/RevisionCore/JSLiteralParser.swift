import Foundation

/// A JavaScript literal value: what `JSLiteralParser` produces.
public enum JSValue: Equatable, Sendable {
    case object([String: JSValue])
    case array([JSValue])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    public subscript(key: String) -> JSValue? {
        if case .object(let dict) = self { return dict[key] }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    /// A string, or a number written as a string (for ids and outcome codes).
    public var looseString: String? {
        switch self {
        case .string(let s): return s
        case .number(let n):
            if n.rounded() == n, abs(n) < 1e15 { return String(Int(n)) }
            return String(n)
        default: return nil
        }
    }

    public var intValue: Int? {
        switch self {
        case .number(let n):
            guard n.rounded() == n, abs(n) < 1e15 else { return nil }
            return Int(n)
        case .string(let s):
            return Int(s.trimmingCharacters(in: .whitespaces))
        default:
            return nil
        }
    }

    public var arrayValue: [JSValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var objectValue: [String: JSValue]? {
        if case .object(let o) = self { return o }
        return nil
    }
}

public struct JSParseError: Error, CustomStringConvertible, Equatable, Sendable {
    public let message: String
    public let offset: Int
    public var description: String { "\(message) at byte \(offset)" }
}

/// A small, lenient parser for the JavaScript literals the revision site keeps its data in.
///
/// Supports objects, arrays, strings in `"` or `'` with JS escapes, numbers,
/// `true`/`false`/`null`, unquoted identifier keys, trailing commas and `//` / `/* */` comments.
public enum JSLiteralParser {
    /// Finds `const NAME =` in `source` and parses the literal that follows. The rest of the file is ignored.
    public static func value(ofConst name: String, in source: String) throws -> JSValue {
        let pattern = "\\bconst\\s+" + NSRegularExpression.escapedPattern(for: name) + "\\s*="
        let regex = try NSRegularExpression(pattern: pattern)
        let ns = source as NSString
        guard let match = regex.firstMatch(in: source, range: NSRange(location: 0, length: ns.length)) else {
            throw JSParseError(message: "No declaration for \(name)", offset: 0)
        }
        let tail = ns.substring(from: match.range.location + match.range.length)
        var parser = Parser(bytes: Array(tail.utf8))
        return try parser.parseValue()
    }

    /// Parses one literal at the start of `text` (after optional whitespace/comments), ignoring anything after it.
    public static func parse(_ text: String) throws -> JSValue {
        var parser = Parser(bytes: Array(text.utf8))
        return try parser.parseValue()
    }
}

private struct Parser {
    let bytes: [UInt8]
    var pos = 0

    init(bytes: [UInt8]) { self.bytes = bytes }

    func error(_ message: String) -> JSParseError { JSParseError(message: message, offset: pos) }

    var current: UInt8? { pos < bytes.count ? bytes[pos] : nil }

    mutating func skipTrivia() throws {
        while pos < bytes.count {
            let c = bytes[pos]
            switch c {
            case 0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C:
                pos += 1
            case 0xC2 where pos + 1 < bytes.count && bytes[pos + 1] == 0xA0: // NBSP
                pos += 2
            case 0xEF where pos + 2 < bytes.count && bytes[pos + 1] == 0xBB && bytes[pos + 2] == 0xBF: // BOM
                pos += 3
            case UInt8(ascii: "/") where pos + 1 < bytes.count && bytes[pos + 1] == UInt8(ascii: "/"):
                pos += 2
                while pos < bytes.count, bytes[pos] != 0x0A, bytes[pos] != 0x0D { pos += 1 }
            case UInt8(ascii: "/") where pos + 1 < bytes.count && bytes[pos + 1] == UInt8(ascii: "*"):
                pos += 2
                var closed = false
                while pos + 1 < bytes.count {
                    if bytes[pos] == UInt8(ascii: "*") && bytes[pos + 1] == UInt8(ascii: "/") {
                        pos += 2
                        closed = true
                        break
                    }
                    pos += 1
                }
                if !closed { throw error("Unterminated comment") }
            default:
                return
            }
        }
    }

    mutating func parseValue() throws -> JSValue {
        try skipTrivia()
        guard let c = current else { throw error("Unexpected end of input") }
        switch c {
        case UInt8(ascii: "{"): return try parseObject()
        case UInt8(ascii: "["): return try parseArray()
        case UInt8(ascii: "\""), UInt8(ascii: "'"): return .string(try parseString())
        case UInt8(ascii: "-"), UInt8(ascii: "+"), UInt8(ascii: "."), UInt8(ascii: "0")...UInt8(ascii: "9"):
            return .number(try parseNumber())
        default:
            if isIdentifierStart(c) {
                let word = parseIdentifier()
                switch word {
                case "true": return .bool(true)
                case "false": return .bool(false)
                case "null", "undefined": return .null
                case "NaN": return .number(.nan)
                case "Infinity": return .number(.infinity)
                default: throw error("Unexpected identifier \(word)")
                }
            }
            throw error("Unexpected character \(Character(Unicode.Scalar(c)))")
        }
    }

    mutating func parseObject() throws -> JSValue {
        pos += 1 // {
        var dict: [String: JSValue] = [:]
        while true {
            try skipTrivia()
            guard let c = current else { throw error("Unterminated object") }
            if c == UInt8(ascii: "}") { pos += 1; return .object(dict) }
            let key: String
            if c == UInt8(ascii: "\"") || c == UInt8(ascii: "'") {
                key = try parseString()
            } else if isIdentifierStart(c) {
                key = parseIdentifier()
            } else if isDigit(c) {
                let n = try parseNumber()
                key = JSValue.number(n).looseString ?? String(n)
            } else {
                throw error("Expected object key")
            }
            try skipTrivia()
            guard current == UInt8(ascii: ":") else { throw error("Expected ':' after key \(key)") }
            pos += 1
            dict[key] = try parseValue()
            try skipTrivia()
            guard let next = current else { throw error("Unterminated object") }
            if next == UInt8(ascii: ",") { pos += 1; continue }
            if next == UInt8(ascii: "}") { pos += 1; return .object(dict) }
            throw error("Expected ',' or '}' in object")
        }
    }

    mutating func parseArray() throws -> JSValue {
        pos += 1 // [
        var items: [JSValue] = []
        while true {
            try skipTrivia()
            guard let c = current else { throw error("Unterminated array") }
            if c == UInt8(ascii: "]") { pos += 1; return .array(items) }
            items.append(try parseValue())
            try skipTrivia()
            guard let next = current else { throw error("Unterminated array") }
            if next == UInt8(ascii: ",") { pos += 1; continue }
            if next == UInt8(ascii: "]") { pos += 1; return .array(items) }
            throw error("Expected ',' or ']' in array")
        }
    }

    mutating func parseString() throws -> String {
        let quote = bytes[pos]
        pos += 1
        var out: [UInt8] = []
        var runStart = pos
        while pos < bytes.count {
            let c = bytes[pos]
            if c == quote {
                out.append(contentsOf: bytes[runStart..<pos])
                pos += 1
                return String(decoding: out, as: UTF8.self)
            }
            if c == UInt8(ascii: "\\") {
                out.append(contentsOf: bytes[runStart..<pos])
                pos += 1
                guard pos < bytes.count else { break }
                let e = bytes[pos]
                pos += 1
                switch e {
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0C)
                case UInt8(ascii: "v"): out.append(0x0B)
                case UInt8(ascii: "0") where !(pos < bytes.count && isDigit(bytes[pos])): out.append(0x00)
                case 0x0A: break // line continuation
                case 0x0D:
                    if current == 0x0A { pos += 1 }
                case UInt8(ascii: "u"):
                    let scalar = try parseUnicodeEscape()
                    out.append(contentsOf: Array(String(Character(scalar)).utf8))
                case UInt8(ascii: "x"):
                    guard pos + 2 <= bytes.count, let v = hexValue(bytes[pos..<pos + 2]) else {
                        throw error("Bad \\x escape")
                    }
                    pos += 2
                    out.append(contentsOf: Array(String(Character(Unicode.Scalar(UInt8(v)))).utf8))
                default:
                    // \" \' \\ \/ and any other character stand for themselves.
                    out.append(e)
                }
                runStart = pos
                continue
            }
            if c == 0x0A && quote != UInt8(ascii: "`") {
                throw error("Unterminated string")
            }
            pos += 1
        }
        throw error("Unterminated string")
    }

    mutating func parseUnicodeEscape() throws -> Unicode.Scalar {
        func readUnit() throws -> UInt32 {
            if current == UInt8(ascii: "{") {
                pos += 1
                let start = pos
                while pos < bytes.count, bytes[pos] != UInt8(ascii: "}") { pos += 1 }
                guard pos < bytes.count, let v = hexValue(bytes[start..<pos]) else { throw error("Bad \\u{} escape") }
                pos += 1
                return v
            }
            guard pos + 4 <= bytes.count, let v = hexValue(bytes[pos..<pos + 4]) else { throw error("Bad \\u escape") }
            pos += 4
            return v
        }
        let first = try readUnit()
        if (0xD800...0xDBFF).contains(first),
           pos + 6 <= bytes.count, bytes[pos] == UInt8(ascii: "\\"), bytes[pos + 1] == UInt8(ascii: "u"),
           let low = hexValue(bytes[pos + 2..<pos + 6]), (0xDC00...0xDFFF).contains(low) {
            pos += 6
            let combined = 0x10000 + ((first - 0xD800) << 10) + (low - 0xDC00)
            return Unicode.Scalar(combined) ?? "\u{FFFD}"
        }
        return Unicode.Scalar(first) ?? "\u{FFFD}"
    }

    mutating func parseNumber() throws -> Double {
        let start = pos
        if current == UInt8(ascii: "-") || current == UInt8(ascii: "+") { pos += 1 }
        if current == UInt8(ascii: "0"), pos + 1 < bytes.count, bytes[pos + 1] | 0x20 == UInt8(ascii: "x") {
            pos += 2
            let digitsStart = pos
            while let c = current, hexDigit(c) != nil { pos += 1 }
            guard let v = hexValue(bytes[digitsStart..<pos]) else { throw error("Bad hex number") }
            return bytes[start] == UInt8(ascii: "-") ? -Double(v) : Double(v)
        }
        while let c = current, isDigit(c) || c == UInt8(ascii: ".") || c == UInt8(ascii: "_") { pos += 1 }
        if let c = current, c | 0x20 == UInt8(ascii: "e") {
            pos += 1
            if current == UInt8(ascii: "-") || current == UInt8(ascii: "+") { pos += 1 }
            while let c = current, isDigit(c) { pos += 1 }
        }
        let text = String(decoding: bytes[start..<pos].filter { $0 != UInt8(ascii: "_") }, as: UTF8.self)
        guard let value = Double(text) else { throw error("Bad number \(text)") }
        return value
    }

    mutating func parseIdentifier() -> String {
        let start = pos
        while let c = current, isIdentifierPart(c) { pos += 1 }
        return String(decoding: bytes[start..<pos], as: UTF8.self)
    }

    func isDigit(_ c: UInt8) -> Bool { c >= UInt8(ascii: "0") && c <= UInt8(ascii: "9") }

    func isIdentifierStart(_ c: UInt8) -> Bool {
        (c >= UInt8(ascii: "a") && c <= UInt8(ascii: "z")) || (c >= UInt8(ascii: "A") && c <= UInt8(ascii: "Z"))
            || c == UInt8(ascii: "_") || c == UInt8(ascii: "$") || c >= 0x80
    }

    func isIdentifierPart(_ c: UInt8) -> Bool { isIdentifierStart(c) || isDigit(c) }

    func hexDigit(_ c: UInt8) -> UInt32? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return UInt32(c - UInt8(ascii: "0"))
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return UInt32(c - UInt8(ascii: "a") + 10)
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return UInt32(c - UInt8(ascii: "A") + 10)
        default: return nil
        }
    }

    func hexValue(_ slice: ArraySlice<UInt8>) -> UInt32? {
        guard !slice.isEmpty, slice.count <= 8 else { return nil }
        var v: UInt32 = 0
        for c in slice {
            guard let d = hexDigit(c) else { return nil }
            v = v << 4 | d
        }
        return v
    }
}
