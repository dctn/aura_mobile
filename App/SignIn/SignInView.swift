import AuraKit
import SwiftUI

/// Email, then the code from the email. The Figma has no frame for this; it uses the same cards and pills.
/// Never log the email or the code.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var email = ""
    @State private var code = ""
    @State private var step: AuthStep?
    @State private var busy = false
    @State private var errorText: String?
    @State private var noticeText: String?

    var body: some View {
        AuraPage {
            SectionTitle(step == nil ? "Sign in" : "Enter your code")
            if let step {
                SignInCodeCard(step: step, code: $code)
            } else {
                SignInEmailCard(email: $email)
            }
            messages
            buttons
        }
        .scrollDismissesKeyboard(.interactively)
    }

    @ViewBuilder
    private var messages: some View {
        if let errorText {
            Text(errorText)
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.red)
        } else if let noticeText {
            Text(noticeText)
                .font(.system(size: 15))
                .foregroundStyle(Color.gray)
        }
    }

    @ViewBuilder
    private var buttons: some View {
        if let step {
            codeButtons(step)
        } else {
            PillButton(title: busy ? "Sending..." : "Send code", fills: true) {
                sendCode()
            }
            .disabled(busy || !emailLooksValid)
            .accessibilityIdentifier("signin-send-code")
        }
    }

    private func codeButtons(_ step: AuthStep) -> some View {
        VStack(spacing: 10) {
            PillButton(title: busy ? "Checking..." : "Continue", fills: true) {
                finish(step)
            }
            .disabled(busy || !SignInCodeRules.allowedLengths(step).contains(code.count))
            .accessibilityIdentifier("signin-continue")
            HStack(spacing: 10) {
                PillButton(title: "Back", style: .secondary, fills: true) {
                    goBack()
                }
                .accessibilityIdentifier("signin-back")
                PillButton(title: "Resend", style: .secondary, fills: true) {
                    resend(step)
                }
                .accessibilityIdentifier("signin-resend")
            }
            .disabled(busy)
        }
    }

    private var trimmedEmail: String {
        email.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var emailLooksValid: Bool {
        let parts = trimmedEmail.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        return !parts[0].isEmpty && parts[1].contains(".")
    }

    private func sendCode() {
        let address = trimmedEmail
        begin()
        Task {
            do {
                let next = try await model.auth.requestCode(email: address)
                code = ""
                step = next
            } catch {
                fail(error)
            }
            busy = false
        }
    }

    private func finish(_ current: AuthStep) {
        let typed = code
        begin()
        Task {
            do {
                let state = try await model.auth.finish(step: current, code: typed)
                model.authState = state
            } catch {
                fail(error)
            }
            busy = false
        }
    }

    private func resend(_ current: AuthStep) {
        begin()
        Task {
            do {
                let next = try await model.auth.resend(step: current)
                code = ""
                step = next
                noticeText = "We sent a new code."
            } catch {
                fail(error)
            }
            busy = false
        }
    }

    private func goBack() {
        step = nil
        code = ""
        errorText = nil
        noticeText = nil
    }

    private func begin() {
        busy = true
        errorText = nil
        noticeText = nil
    }

    private func fail(_ error: Error) {
        model.note(error)
        errorText = SignInCodeRules.plainMessage(error)
    }
}

/// The code lengths a step accepts, and errors in plain words.
private enum SignInCodeRules {
    /// An unconfirmed account can hide as a sign-in, so such a step takes both lengths: 8 signs in, 6 confirms.
    static func allowedLengths(_ step: AuthStep) -> [Int] {
        guard step.alsoConfirm else { return [step.codeLength] }
        let other = step.codeLength == 8 ? 6 : 8
        return [step.codeLength, other].sorted()
    }

    static func plainMessage(_ error: Error) -> String {
        let fallback = "That didn't work. Please try again."
        guard let error = error as? AuraError else { return fallback }
        switch error {
        case .offline:
            return "You're offline. Check your connection and try again."
        case .server(_, let message, _):
            return message.isEmpty ? fallback : message
        default:
            return fallback
        }
    }
}

private struct SignInEmailCard: View {
    @Binding var email: String

    var body: some View {
        AuraCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("We'll email you a code to sign in.")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                TextField("Email", text: $email)
                    .font(.system(size: 18))
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 16)
                    .frame(height: 48)
                    .background(AuraColor.background, in: Capsule())
                    .accessibilityIdentifier("signin-email")
            }
        }
    }
}

private struct SignInCodeCard: View {
    let step: AuthStep
    @Binding var code: String

    private var lengths: [Int] { SignInCodeRules.allowedLengths(step) }

    private var sentTo: String {
        step.destination.isEmpty ? step.email : step.destination
    }

    private var instruction: String {
        let digits = lengths.map { String($0) }.joined(separator: " or ")
        return "Enter the \(digits)-digit code we sent to \(sentTo)."
    }

    var body: some View {
        AuraCard {
            VStack(alignment: .leading, spacing: 12) {
                Text(instruction)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                    .fixedSize(horizontal: false, vertical: true)
                TextField("Code", text: $code)
                    .font(.system(size: 18))
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .padding(.horizontal, 16)
                    .frame(height: 48)
                    .background(AuraColor.background, in: Capsule())
                    .accessibilityIdentifier("signin-code")
                    .onChange(of: code) { _, typed in
                        keepDigits(typed)
                    }
            }
        }
    }

    /// Digits only, up to the longest length this step takes (pasted codes can hold spaces).
    private func keepDigits(_ typed: String) {
        let longest = lengths.last ?? step.codeLength
        let onlyDigits = typed.filter { $0.isASCII && $0.isNumber }
        let digits = String(onlyDigits.prefix(longest))
        if digits != typed {
            code = digits
        }
    }
}
