import AuraKit
import SwiftUI

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// Colours sampled from the Figma PNGs. Never use the Swift reference app's theme.
enum AuraColor {
    static let background = Color(hex: 0xF5F5F5)
    static let card = Color.white
    static let searchField = Color(hex: 0xDFDFE0)
    static let footnote = Color(hex: 0xB4B4B4)
    static let track = Color(hex: 0xEDEDED)

    static let blue = Color(hex: 0x0088FF)
    static let startBlue = Color(hex: 0x108DFD)
    static let green = Color(hex: 0x34C759)
    static let orange = Color(hex: 0xFF9F0A)
    static let indigo = Color(hex: 0x5E5CE6)
    static let red = Color(hex: 0xFF3B30)

    static let burnout = Color(hex: 0xFF6B35)
    static let burnoutFill = Color(hex: 0xF7D9CE)

    static let ringOuter = Color(hex: 0x0081FF)
    static let ringMiddle = Color(hex: 0xFF7D4C)
    static let ringInner = Color(hex: 0x34C759)

    // Run screen accents (Figma "Modes active").
    static let runFocus = Color(hex: 0x007AFF)
    static let runFlow = Color(hex: 0xAF52DE)
    static let runMeeting = Color(hex: 0x5AC8FA)
    static let runBreathe = Color(hex: 0x32ADE6)
    static let runMeditate = Color(hex: 0xAF52DE)
    static let runNap = Color(hex: 0x5E5CE6)
}

/// Each shelf's colour and icon, from the Figma's Recovery tab.
extension Shelf {
    var color: Color {
        switch self {
        case .focus: return AuraColor.blue
        case .breathe: return AuraColor.green
        case .meditate: return AuraColor.orange
        case .sleep: return AuraColor.indigo
        case .rest: return AuraColor.orange
        case .movement: return AuraColor.burnout
        case .mind: return AuraColor.blue
        case .outside: return AuraColor.green
        case .people: return AuraColor.red
        case .fuel: return AuraColor.orange
        }
    }

    var symbol: String {
        switch self {
        case .focus: return "timer"
        case .breathe: return "arrow.clockwise.heart.fill"
        case .meditate: return "brain"
        case .sleep: return "bed.double.fill"
        case .rest: return "brain.head.profile"
        case .movement: return "figure.walk"
        case .mind: return "target"
        case .outside: return "arrow.clockwise.heart.fill"
        case .people: return "person.2.fill"
        case .fuel: return "fork.knife"
        }
    }
}
