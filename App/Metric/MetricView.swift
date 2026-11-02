import AuraKit
import SwiftUI

/// Frame Movement _ Walk Distance. Home's Pins open this too.
struct MetricView: View {
    let name: String
    let field: String
    let unit: String

    var body: some View {
        AuraPage {
            SectionTitle(name)
        }
    }
}
