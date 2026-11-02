import Foundation

// Signing in with an emailed code: a port of demo/src/aura_mobile/services/auth.py, with no AWS SDK.
// Someone with an account gets an 8-digit sign-in code (InitiateAuth USER_AUTH, EMAIL_OTP, RespondToAuthChallenge).
// Someone new is signed up on the spot with no password and gets a 6-digit code (SignUp, ConfirmSignUp), whose session
// signs them in. The pool hides account status, so see aura_mobile/cognito-sign-in.md before changing a branch here.

/// Cognito's own refusal, before it's put into words for a person.
struct CognitoRefusal: Error {
    let kind: String
    let message: String
    let status: Int

    init(kind: String, message: String, status: Int = 400) {
        self.kind = kind.components(separatedBy: "#").last ?? kind
        self.message = message
        self.status = status
    }

    var publicError: AuraError {
        return AuraError.server(code: kind, message: CognitoWords.describe(kind: kind, message: message), status: status)
    }
}

enum CognitoWords {
    static let signInCodeLength = 8
    static let confirmCodeLength = 6
    /// The refusals that mean a refresh token can never work again. Anything else keeps the session.
    static let sessionEnding: Set<String> = ["NotAuthorizedException", "UserNotFoundException"]

    /// The sentence a person reads for a refusal.
    static func describe(kind: String, message: String) -> String {
        let lower = message.lowercased()
        switch kind {
        case "CodeMismatchException":
            return "That code isn't right. Check the email and try again."
        case "ExpiredCodeException":
            return "That code has expired. Send a new one."
        case "CodeDeliveryFailureException":
            return "The code email couldn't be sent. Check the address and try again."
        case "LimitExceededException", "TooManyRequestsException", "TooManyFailedAttemptsException":
            return "Too many attempts. Wait a minute, then try again."
        case "NotAuthorizedException":
            if lower.contains("session") { return "The sign-in took too long. Send a new code." }
            if lower.contains("disabled") { return "This account has been turned off." }
            if lower.contains("refresh token") || lower.contains("revoked") { return "Your session has ended. Sign in again." }
            if lower.contains("attempts") { return "Too many attempts. Wait a minute, then try again." }
        case "InvalidParameterException":
            if lower.contains("email") { return "Enter a valid email address." }
        default:
            break
        }
        return message.isEmpty ? "Sign-in didn't work. Try again." : message
    }

    static func unsupported(_ step: String) -> AuraError {
        return AuraError.server(code: "unsupported_step",
                                message: "Sign-in needs a step this version of the app doesn't support (\(step)). Update the app.",
                                status: 0)
    }

    static func looksLikeEmail(_ email: String) -> Bool {
        let parts = email.components(separatedBy: "@")
        return parts.count == 2 && !parts[0].isEmpty && parts[1].contains(".") && !email.contains(" ")
    }

    /// Cognito's two ways of saying so.
    static func isAlreadyConfirmed(_ message: String) -> Bool {
        let lower = message.lowercased()
        return lower.contains("already confirmed") || lower.contains("current status is confirmed")
    }

    /// For a pool that insists on a password at sign-up: one nobody knows or needs, since every sign-in uses an emailed code.
    static func randomPassword() -> String {
        var generator = SystemRandomNumberGenerator()
        var bytes: [UInt8] = []
        for _ in 0..<24 {
            bytes.append(UInt8.random(in: UInt8.min...UInt8.max, using: &generator))
        }
        return "Cl4!" + Data(bytes).base64EncodedString()
    }
}

/// One JSON POST per call. The app client is public, so there are no AWS credentials and no client secret.
struct CognitoClient: Sendable {
    let endpoint: URL
    let clientId: String
    let session: URLSession
    let now: @Sendable () -> Date

    /// Throws `CognitoRefusal` for a refusal and `AuraError.offline` for no answer.
    func call(_ target: String, _ body: [String: Any], timeout: TimeInterval = 20) async throws -> [String: Any] {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/x-amz-json-1.1", forHTTPHeaderField: "Content-Type")
        request.setValue("AWSCognitoIdentityProviderService.\(target)", forHTTPHeaderField: "X-Amz-Target")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let answer: (Data, URLResponse)
        do {
            answer = try await session.data(for: request)
        } catch {
            throw NetworkFailure.mapped(error)
        }
        let status = (answer.1 as? HTTPURLResponse)?.statusCode ?? 0
        let object = try? JSONSerialization.jsonObject(with: answer.0)
        let json = (object as? [String: Any]) ?? [:]
        if !(200..<300).contains(status) {
            let kind = (json["__type"] as? String) ?? "HTTP\(status)"
            let message = (json["message"] as? String) ?? (json["Message"] as? String) ?? ""
            throw CognitoRefusal(kind: kind, message: message, status: status)
        }
        return json
    }

    /// REFRESH_TOKEN_AUTH. Throws `AuraError.signedOut` only when Cognito refused this token or its user.
    func refresh(_ refreshToken: String) async throws -> AuthTokens {
        let body: [String: Any] = ["AuthFlow": "REFRESH_TOKEN_AUTH", "ClientId": clientId,
                                   "AuthParameters": ["REFRESH_TOKEN": refreshToken]]
        let result: [String: Any]
        do {
            result = try await call("InitiateAuth", body)
        } catch let refused as CognitoRefusal {
            if CognitoWords.sessionEnding.contains(refused.kind) {
                throw AuraError.signedOut
            }
            throw refused.publicError
        }
        // A 200 without tokens isn't Cognito refusing the token, so it must not end the session.
        guard let auth = result["AuthenticationResult"] as? [String: Any] else {
            throw CognitoWords.unsupported((result["ChallengeName"] as? String) ?? "REFRESH_WITHOUT_TOKENS")
        }
        let tokens = AuthTokens.issued(accessToken: (auth["AccessToken"] as? String) ?? "",
                                       idToken: (auth["IdToken"] as? String) ?? "",
                                       refreshToken: (auth["RefreshToken"] as? String) ?? refreshToken,
                                       expiresIn: auth["ExpiresIn"], now: now())
        if tokens.sub.isEmpty {
            throw CognitoWords.unsupported("TOKENS_WITHOUT_IDENTITY")
        }
        return tokens
    }

    /// Tokens from a sign-in answer.
    func tokens(from result: [String: Any], step: String) throws -> AuthTokens {
        guard let auth = result["AuthenticationResult"] as? [String: Any],
              let refreshToken = auth["RefreshToken"] as? String, !refreshToken.isEmpty else {
            throw CognitoWords.unsupported((result["ChallengeName"] as? String) ?? step)
        }
        let tokens = AuthTokens.issued(accessToken: (auth["AccessToken"] as? String) ?? "",
                                       idToken: (auth["IdToken"] as? String) ?? "",
                                       refreshToken: refreshToken, expiresIn: auth["ExpiresIn"], now: now())
        if tokens.sub.isEmpty {
            throw CognitoWords.unsupported("TOKENS_WITHOUT_IDENTITY")
        }
        return tokens
    }
}

/// Holds the tokens and keeps them fresh. However many ask at once, one refresh runs.
actor AuthSession {
    /// Refresh when less than this is left.
    static let refreshMargin: TimeInterval = 120

    private let client: CognitoClient
    private let store: any TokenStore
    private var tokens: AuthTokens?
    private var loaded = false
    /// Bumped when a session begins or ends, so a refresh in flight across a sign-out can't bring the old account back.
    private var epoch = 0
    private var refreshing: Task<AuthTokens, Error>?
    /// Addresses confirmed here with no session to sign in with: a sign-in code is what completes them.
    private var confirmedWithoutSession: Set<String> = []

    init(client: CognitoClient, store: any TokenStore) {
        self.client = client
        self.store = store
    }

    func restore() -> AuthTokens? {
        var saved: AuthTokens?
        if let found = try? store.load(), !found.sub.isEmpty {
            saved = found
        }
        tokens = saved
        loaded = true
        startEpoch()
        return saved
    }

    func begin(_ fresh: AuthTokens) {
        tokens = fresh
        loaded = true
        startEpoch()
        // If saving fails the session still works until the app quits.
        try? store.save(fresh)
    }

    /// Ends the session here and returns the tokens that were held, for a revoke.
    @discardableResult
    func clear() -> AuthTokens? {
        loadIfNeeded()
        let held = tokens
        tokens = nil
        startEpoch()
        try? store.clear()
        return held
    }

    func accessToken() async throws -> String {
        loadIfNeeded()
        guard let current = tokens else { throw AuraError.signedOut }
        if current.expiresAt.timeIntervalSince(client.now()) >= AuthSession.refreshMargin {
            return current.accessToken
        }
        let fresh = try await refreshed()
        return fresh.accessToken
    }

    func refreshedToken(rejected: String) async throws -> String {
        loadIfNeeded()
        guard let current = tokens else { throw AuraError.signedOut }
        // Someone else already replaced the token the API refused.
        if current.accessToken != rejected { return current.accessToken }
        let fresh = try await refreshed()
        return fresh.accessToken
    }

    func tokenRejected(_ token: String) {
        if tokens?.accessToken == token {
            clear()
        }
    }

    func noteConfirmedWithoutSession(_ email: String) {
        confirmedWithoutSession.insert(email)
    }

    func takeConfirmedWithoutSession(_ email: String) -> Bool {
        return confirmedWithoutSession.remove(email) != nil
    }

    private func loadIfNeeded() {
        if !loaded {
            _ = restore()
        }
    }

    private func startEpoch() {
        epoch += 1
        refreshing = nil
    }

    /// Every caller waits on the same task, which a caller's cancellation doesn't stop.
    private func refreshed() async throws -> AuthTokens {
        if let running = refreshing {
            return try await running.value
        }
        guard let current = tokens else { throw AuraError.signedOut }
        let started = epoch
        let task = Task<AuthTokens, Error> {
            return try await self.runRefresh(current, started)
        }
        refreshing = task
        return try await task.value
    }

    private func runRefresh(_ current: AuthTokens, _ started: Int) async throws -> AuthTokens {
        defer {
            if epoch == started {
                refreshing = nil
            }
        }
        do {
            let fresh = try await client.refresh(current.refreshToken)
            // Signed out, or in again, while this ran: saving these would bring back the account that left.
            if epoch != started {
                throw AuraError.signedOut
            }
            tokens = fresh
            try? store.save(fresh)
            return fresh
        } catch AuraError.signedOut {
            if epoch == started {
                clear()
            }
            throw AuraError.signedOut
        }
    }
}

public final class CognitoAuth: AuthService, AccessTokenSource, Sendable {
    private let client: CognitoClient
    private let session: AuthSession

    public init(config: AppConfig, store: any TokenStore, session urlSession: URLSession = URLSession.shared,
                now: @escaping @Sendable () -> Date = { Date() }) {
        let endpoint = URL(string: "https://cognito-idp.\(config.region).amazonaws.com/") ?? URL(fileURLWithPath: "/")
        let cognito = CognitoClient(endpoint: endpoint, clientId: config.clientId, session: urlSession, now: now)
        self.client = cognito
        self.session = AuthSession(client: cognito, store: store)
    }

    // MARK: AuthService

    public func restore() async -> AuthState {
        guard let tokens = await session.restore() else { return AuthState.signedOut }
        return AuthState.signedIn(email: tokens.email)
    }

    /// Sends a code, creating the account first if there is none.
    public func requestCode(email: String) async throws -> AuthStep {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !CognitoWords.looksLikeEmail(address) {
            throw AuraError.server(code: "invalid_email", message: "Enter a valid email address.", status: 0)
        }
        do {
            return try await start(address)
        } catch let refused as CognitoRefusal {
            throw refused.publicError
        }
    }

    public func finish(step: AuthStep, code: String) async throws -> AuthState {
        let digits = code.filter { $0.isASCII && $0.isNumber }
        // A 6-digit code on a both-codes step means this account was never confirmed.
        let isConfirmation = step.kind == .confirmSignUp
            || (step.alsoConfirm && digits.count == CognitoWords.confirmCodeLength)
        do {
            let tokens: AuthTokens
            if isConfirmation {
                tokens = try await confirmAndSignIn(email: step.email, digits: digits)
            } else {
                tokens = try await answerChallenge(step, digits: digits)
            }
            await session.begin(tokens)
            return AuthState.signedIn(email: tokens.email)
        } catch let refused as CognitoRefusal {
            throw refused.publicError
        }
    }

    public func resend(step: AuthStep) async throws -> AuthStep {
        do {
            switch step.kind {
            case .signIn:
                let signIn = try await requestSignInCode(step.email)
                return try await withConfirmation(signIn)
            case .confirmSignUp:
                // A confirmed account gets a fake success from ResendConfirmationCode and no email.
                let confirmed = await session.takeConfirmedWithoutSession(step.email)
                if confirmed {
                    return try await requestSignInCode(step.email)
                }
                return try await resendConfirmation(step.email)
            }
        } catch let refused as CognitoRefusal {
            throw refused.publicError
        }
    }

    /// Clears the session first, then revokes as a best effort: signing out has to work offline.
    public func signOut() async {
        guard let held = await session.clear() else { return }
        let body: [String: Any] = ["ClientId": client.clientId, "Token": held.refreshToken]
        _ = try? await client.call("RevokeToken", body, timeout: 5)
    }

    // MARK: AccessTokenSource

    func accessToken() async throws -> String {
        return try await session.accessToken()
    }

    func refreshedToken(rejected: String) async throws -> String {
        return try await session.refreshedToken(rejected: rejected)
    }

    func tokenRejected(_ token: String) async {
        await session.tokenRejected(token)
    }

    // MARK: The steps

    private func start(_ address: String) async throws -> AuthStep {
        do {
            let signIn = try await requestSignInCode(address)
            return try await withConfirmation(signIn)
        } catch let refused as CognitoRefusal where refused.kind == "UserNotFoundException" {
            do {
                return try await signUp(address)
            } catch let exists as CognitoRefusal where exists.kind == "UsernameExistsException" {
                // Hidden earlier: the account exists but was never confirmed.
                return try await resendConfirmation(address)
            }
        } catch let refused as CognitoRefusal where refused.kind == "UserNotConfirmedException" {
            return try await resendConfirmation(address)
        }
    }

    private func requestSignInCode(_ email: String) async throws -> AuthStep {
        let first: [String: Any] = ["AuthFlow": "USER_AUTH", "ClientId": client.clientId,
                                    "AuthParameters": ["USERNAME": email, "PREFERRED_CHALLENGE": "EMAIL_OTP"]]
        var result = try await client.call("InitiateAuth", first)
        if (result["ChallengeName"] as? String) == "SELECT_CHALLENGE",
           let selectSession = result["Session"] as? String, !selectSession.isEmpty {
            // The pool hides unknown emails: it offers password challenges only, and picking EMAIL_OTP is then refused.
            if let offered = result["AvailableChallenges"] as? [String], !offered.contains("EMAIL_OTP") {
                throw CognitoRefusal(kind: "UserNotFoundException", message: "No account can take an email code")
            }
            let pick: [String: Any] = ["ChallengeName": "SELECT_CHALLENGE", "ClientId": client.clientId, "Session": selectSession,
                                       "ChallengeResponses": ["USERNAME": email, "ANSWER": "EMAIL_OTP"]]
            do {
                result = try await client.call("RespondToAuthChallenge", pick)
            } catch let refused as CognitoRefusal where refused.message.lowercased().contains("challenge is not available") {
                throw CognitoRefusal(kind: "UserNotFoundException", message: refused.message)
            }
        }
        guard (result["ChallengeName"] as? String) == "EMAIL_OTP",
              let otpSession = result["Session"] as? String, !otpSession.isEmpty else {
            throw CognitoWords.unsupported((result["ChallengeName"] as? String) ?? "NO_CHALLENGE")
        }
        let parameters = result["ChallengeParameters"] as? [String: Any]
        let destination = (parameters?["CODE_DELIVERY_DESTINATION"] as? String) ?? ""
        return AuthStep(kind: .signIn, email: email, session: otpSession, codeLength: CognitoWords.signInCodeLength,
                        alsoConfirm: false, destination: destination.isEmpty ? email : destination)
    }

    /// Also asks for a confirmation code. A confirmed account gets a fake success and no email; for an unconfirmed one
    /// the sign-in challenge was the fake. Exactly one real email goes out.
    private func withConfirmation(_ step: AuthStep) async throws -> AuthStep {
        let body: [String: Any] = ["ClientId": client.clientId, "Username": step.email]
        do {
            _ = try await client.call("ResendConfirmationCode", body)
        } catch is CognitoRefusal {
            return step
        }
        var both = step
        both.alsoConfirm = true
        return both
    }

    private func signUp(_ email: String) async throws -> AuthStep {
        let body: [String: Any] = ["ClientId": client.clientId, "Username": email,
                                   "UserAttributes": [["Name": "email", "Value": email]]]
        let result: [String: Any]
        do {
            result = try await client.call("SignUp", body)
        } catch let refused as CognitoRefusal
            where refused.kind == "InvalidPasswordException" || refused.message.lowercased().contains("password") {
            var withPassword = body
            withPassword["Password"] = CognitoWords.randomPassword()
            result = try await client.call("SignUp", withPassword)
        }
        var signUpSession = result["Session"] as? String
        if signUpSession?.isEmpty == true {
            signUpSession = nil
        }
        return confirmStep(email, session: signUpSession, result)
    }

    private func resendConfirmation(_ email: String) async throws -> AuthStep {
        let body: [String: Any] = ["ClientId": client.clientId, "Username": email]
        let result: [String: Any]
        do {
            result = try await client.call("ResendConfirmationCode", body)
        } catch let refused as CognitoRefusal where CognitoWords.isAlreadyConfirmed(refused.message) {
            // Confirmed, but the sign-in after it never happened: only a sign-in code finishes that.
            return try await requestSignInCode(email)
        }
        return confirmStep(email, session: nil, result)
    }

    private func confirmStep(_ email: String, session: String?, _ result: [String: Any]) -> AuthStep {
        let details = result["CodeDeliveryDetails"] as? [String: Any]
        let destination = (details?["Destination"] as? String) ?? ""
        return AuthStep(kind: .confirmSignUp, email: email, session: session, codeLength: CognitoWords.confirmCodeLength,
                        alsoConfirm: false, destination: destination.isEmpty ? email : destination)
    }

    private func answerChallenge(_ step: AuthStep, digits: String) async throws -> AuthTokens {
        let body: [String: Any] = ["ChallengeName": "EMAIL_OTP", "ClientId": client.clientId, "Session": step.session ?? "",
                                   "ChallengeResponses": ["USERNAME": step.email, "EMAIL_OTP_CODE": digits]]
        let result = try await client.call("RespondToAuthChallenge", body)
        return try client.tokens(from: result, step: "EMAIL_OTP")
    }

    private func confirmAndSignIn(email: String, digits: String) async throws -> AuthTokens {
        let confirmBody: [String: Any] = ["ClientId": client.clientId, "Username": email, "ConfirmationCode": digits]
        let confirmed: [String: Any]
        do {
            confirmed = try await client.call("ConfirmSignUp", confirmBody)
        } catch let refused as CognitoRefusal where CognitoWords.isAlreadyConfirmed(refused.message) {
            throw await signInCodeNeeded(email)
        }
        guard let confirmedSession = confirmed["Session"] as? String, !confirmedSession.isEmpty else {
            throw await signInCodeNeeded(email)
        }
        let body: [String: Any] = ["AuthFlow": "USER_AUTH", "ClientId": client.clientId, "Session": confirmedSession,
                                   "AuthParameters": ["USERNAME": email]]
        let result = try await client.call("InitiateAuth", body)
        return try client.tokens(from: result, step: "SIGN_UP_SESSION")
    }

    /// The account is confirmed but no session came back, so `resend` sends a sign-in code next.
    private func signInCodeNeeded(_ email: String) async -> AuraError {
        await session.noteConfirmedWithoutSession(email)
        return AuraError.server(code: "confirmed_without_session",
                                message: "Your account is confirmed. Send a new code to sign in.", status: 0)
    }
}
