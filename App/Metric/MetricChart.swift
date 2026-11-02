import AuraKit
import SwiftUI

/// The three range buttons: a glass capsule with a white lozenge behind the chosen one.
struct MetricRangeSwitch: View {
    @Binding var selection: MetricRange
    @Namespace private var lozenge

    init(selection: Binding<MetricRange>) {
        self._selection = selection
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(MetricRange.allCases.enumerated()), id: \.element.id) { index, range in
                button(range, index: index)
            }
        }
        .padding(4)
        .glassEffect(.regular, in: Capsule())
    }

    private func button(_ range: MetricRange, index: Int) -> some View {
        let chosen = range == selection
        return Button {
            withAnimation(.snappy) { selection = range }
        } label: {
            Text(range.title)
                .font(.system(size: 15, weight: chosen ? .semibold : .medium))
                .foregroundStyle(chosen ? Color.black : MetricText.grey)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background {
                    if chosen {
                        Capsule()
                            .fill(Color.white)
                            .shadow(color: Color.black.opacity(0.08), radius: 5, y: 1)
                            .matchedGeometryEffect(id: "chosen", in: lozenge)
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("metric-range-\(index)")
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}

/// Rounded orange bars on grey tracks, one a day. A day with no value shows only its track.
struct MetricBarChart: View {
    static let height: CGFloat = 220

    let name: String
    let points: [MetricPoint]
    let selectedIndex: Int?
    let onSelect: (Int) -> Void

    private var fractions: [Double?] {
        MetricMaths.barFractions(points.map(\.value))
    }

    var body: some View {
        GeometryReader { geometry in
            let width = barWidth(in: geometry.size.width)
            HStack(alignment: .bottom, spacing: gap(in: geometry.size.width, barWidth: width)) {
                ForEach(Array(fractions.enumerated()), id: \.offset) { index, fraction in
                    bar(index: index, fraction: fraction, width: width)
                }
            }
        }
        .frame(height: Self.height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(name) chart"))
    }

    private func barWidth(in total: CGFloat) -> CGFloat {
        let count = CGFloat(max(points.count, 1))
        return min(14, max(2, total / count * 0.6))
    }

    private func gap(in total: CGFloat, barWidth: CGFloat) -> CGFloat {
        let count = CGFloat(points.count)
        guard count > 1 else { return 0 }
        return max(0, (total - count * barWidth) / (count - 1))
    }

    private func bar(index: Int, fraction: Double?, width: CGFloat) -> some View {
        ZStack(alignment: .bottom) {
            Capsule()
                .fill(AuraColor.track)
            if let fraction {
                Capsule()
                    .fill(AuraColor.burnout)
                    .frame(height: filledHeight(fraction, width: width))
                    .opacity(selectedIndex == nil || selectedIndex == index ? 1 : 0.4)
            }
        }
        .frame(width: width, height: Self.height)
        .contentShape(Rectangle())
        .onTapGesture {
            if fraction != nil { onSelect(index) }
        }
    }

    /// Never shorter than a dot, so a measured zero still differs from a day with no value.
    private func filledHeight(_ fraction: Double, width: CGFloat) -> CGFloat {
        let height = CGFloat(fraction) * Self.height
        return max(width, height)
    }
}
