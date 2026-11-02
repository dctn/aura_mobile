import AuraKit
import SwiftUI

/// The Now cards' two colour sets, sampled from iPhone 18 Pro - 5: nap cards and coffee cards.
enum RecoveryCardStyle {
    case cool, warm

    init(mode: Mode) {
        if mode.id == "caffeine-nap" {
            self = .warm
            return
        }
        switch mode.shelf {
        case .fuel, .movement, .outside, .people:
            self = .warm
        case .focus, .breathe, .meditate, .sleep, .rest, .mind:
            self = .cool
        }
    }

    /// Four rows of four, top left first.
    var meshColors: [Color] {
        switch self {
        case .cool: return Self.colors(Self.coolMesh)
        case .warm: return Self.colors(Self.warmMesh)
        }
    }

    /// The detail page's header, left to right (iPhone 18 Pro - 6's top row for the nap card).
    var headerColors: [Color] {
        switch self {
        case .cool: return Self.colors(Self.coolHeader)
        case .warm: return Self.colors(Self.warmHeader)
        }
    }

    private static let coolMesh: [UInt32] = [
        0x506AFF, 0x6650FF, 0xAE43FF, 0xE741E7,
        0x36A1FF, 0x306DFF, 0x573EFF, 0xA539FF,
        0x29C7F4, 0x2795FE, 0x3E45FF, 0x7034FF,
        0x21DDDA, 0x1CB9EB, 0x1E5CFB, 0x3F37FF,
    ]
    private static let warmMesh: [UInt32] = [
        0xC0F900, 0xA3FF00, 0x95FF00, 0x94FF00,
        0xEDEE00, 0xE6F400, 0xDCF300, 0xD0F700,
        0xFFBF00, 0xFCC800, 0xF7CC00, 0xF7E000,
        0xFE330B, 0xFF7600, 0xFF9C00, 0xFCBD00,
    ]
    private static let coolHeader: [UInt32] = [0x3F92FE, 0x3E73FF, 0x3D50FE, 0x8A3EFE, 0xC23EF8]
    private static let warmHeader: [UInt32] = [0xFF9C00, 0xFCC800, 0xE6F400, 0xA3FF00, 0x95FF00]

    private static func colors(_ values: [UInt32]) -> [Color] {
        return values.map { Color(hex: $0) }
    }
}

struct RecoveryMesh: View {
    let style: RecoveryCardStyle

    private static let points: [SIMD2<Float>] = {
        var all: [SIMD2<Float>] = []
        for row in 0..<4 {
            for column in 0..<4 {
                all.append(SIMD2<Float>(Float(column) / 3, Float(row) / 3))
            }
        }
        return all
    }()

    var body: some View {
        MeshGradient(width: 4, height: 4, points: Self.points, colors: style.meshColors)
    }
}

/// One card on the Now carousel.
struct RecoveryNowCard: View {
    let mode: Mode

    private var symbol: String {
        return mode.id == "caffeine-nap" ? "fork.knife" : mode.shelf.symbol
    }

    var body: some View {
        RecoveryMesh(style: RecoveryCardStyle(mode: mode))
            .frame(width: 167, height: 231)
            .overlay(alignment: .topTrailing) {
                Image(systemName: symbol)
                    .font(.system(size: 19))
                    .foregroundStyle(.white)
                    .padding(.top, 20)
                    .padding(.trailing, 20)
            }
            .overlay(alignment: .bottomLeading) {
                Text(mode.nowTitle ?? mode.name)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.leading)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 16)
            }
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}
