import SwiftUI

/// Frame Walk Distance _ Sources. The backend has no per-metric sources, so this lists only what is true.
struct MetricSourcesView: View {
    let name: String

    var body: some View {
        AuraPage {
            SectionTitle("Sources")
                .metricTitleInset()
            SearchGroupCard {
                appleHealth
            }
            Footnote(text: "\(name) data is stored in your Aura Clarus account in the cloud. The app can't show yet which device recorded each value.")
                .metricTitleInset()
        }
        .metricBackBar(name)
    }

    private var appleHealth: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Apple Health")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.black)
            Text("Not connected")
                .font(.system(size: 14))
                .foregroundStyle(MetricText.grey)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .frame(minHeight: 66)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("source-apple-health")
    }
}
