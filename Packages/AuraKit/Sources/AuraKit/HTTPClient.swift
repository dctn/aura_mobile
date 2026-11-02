import Foundation

/// Waits between retries. Tests pass one that returns at once.
public typealias AuraSleep = @Sendable (TimeInterval) async throws -> Void

/// What the HTTP client needs from the sign-in layer.
protocol AccessTokenSource: Sendable {
    /// A token that's good now, refreshed first when it's close to expiry.
    func accessToken() async throws -> String
    /// The API answered 401 to `rejected`: a newer token, refreshing only if nobody already has.
    func refreshedToken(rejected: String) async throws -> String
    /// The API refused a token that had just been refreshed, so the session is over.
    func tokenRejected(_ token: String) async
}

/// At most `limit` requests open at once. Dev's account runs few Lambdas at a time, so leave room for the backend.
actor RequestLimiter {
    private let limit: Int
    private var open = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) {
        self.limit = limit
    }

    func acquire() async {
        if open < limit {
            open += 1
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiting.append(continuation)
        }
    }

    /// Hands the slot straight to the next in line, if there is one.
    func release() {
        if waiting.isEmpty {
            open -= 1
        } else {
            let next = waiting.removeFirst()
            next.resume()
        }
    }
}

enum NetworkFailure {
    /// A view going away cancels its call, which isn't the same as being offline.
    static func mapped(_ error: Error) -> Error {
        if error is CancellationError || error is AuraError { return error }
        if let urlError = error as? URLError, urlError.code == .cancelled { return CancellationError() }
        if Task.isCancelled { return CancellationError() }
        return AuraError.offline
    }
}

enum URLEscape {
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    /// Stricter than URLComponents, which leaves `+` alone: API Gateway would read that as a space, and cursors can hold it.
    static func component(_ text: String) -> String {
        return text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? text
    }
}

/// An error body is `{"error": {code, message}}`; API Gateway's own are `{"message"}`.
private struct ServiceFailure {
    let hasErrorBody: Bool
    let code: String
    let message: String

    init(status: Int, data: Data) {
        let object = try? JSONSerialization.jsonObject(with: data)
        let json = object as? [String: Any]
        if let error = json?["error"] as? [String: Any] {
            hasErrorBody = true
            code = (error["code"] as? String) ?? "http_\(status)"
            message = (error["message"] as? String) ?? "HTTP \(status)"
        } else {
            hasErrorBody = false
            code = "http_\(status)"
            message = (json?["message"] as? String) ?? "HTTP \(status)"
        }
    }
}

/// One HTTP client for the ten services, each at its own URL.
actor ServiceClient {
    static let throttleDelays: [TimeInterval] = [1, 2, 4, 8]
    static let consentWaits: [TimeInterval] = [1, 2, 3, 4]
    /// How long after a grant a `consent_required` answer is taken to be Health Data's copy lagging.
    static let consentWindow: TimeInterval = 30
    static let maxInFlight = 6
    static let realSleep: AuraSleep = { (seconds: TimeInterval) in
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    private let config: AppConfig
    private let tokens: any AccessTokenSource
    private let session: URLSession
    private let pause: AuraSleep
    private let now: @Sendable () -> Date
    private let limiter: RequestLimiter
    private var consentGrantedAt: Date?

    init(config: AppConfig, tokens: any AccessTokenSource, session: URLSession, sleep: @escaping AuraSleep,
         now: @escaping @Sendable () -> Date) {
        self.config = config
        self.tokens = tokens
        self.session = session
        self.pause = sleep
        self.now = now
        self.limiter = RequestLimiter(limit: ServiceClient.maxInFlight)
    }

    func noteConsentGranted() {
        consentGrantedAt = now()
    }

    /// One call: returns the body of a 2xx, throws `AuraError` for anything else. `path` must already be escaped.
    func send(_ service: ServiceName, _ method: String, _ path: String, query: [String: String] = [:],
              body: Data? = nil) async throws -> Data {
        let url = try makeURL(service, path, query)
        var refreshed = false
        var throttled = 0
        var consentTries = 0
        while true {
            let token = try await tokens.accessToken()
            let request = makeRequest(url, method, body, token)
            let reply = try await transmit(request)
            if (200..<300).contains(reply.status) { return reply.data }
            if reply.status == 401 {
                if refreshed {
                    await tokens.tokenRejected(token)
                    throw AuraError.signedOut
                }
                _ = try await tokens.refreshedToken(rejected: token)
                refreshed = true
                continue
            }
            let failure = ServiceFailure(status: reply.status, data: reply.data)
            if reply.status == 503, !failure.hasErrorBody, throttled < ServiceClient.throttleDelays.count {
                try await pause(ServiceClient.throttleDelays[throttled])
                throttled += 1
                continue
            }
            if reply.status == 403, failure.code == "consent_required" {
                guard consentJustGranted, consentTries < ServiceClient.consentWaits.count else {
                    throw AuraError.consentRequired
                }
                try await pause(ServiceClient.consentWaits[consentTries])
                consentTries += 1
                continue
            }
            throw AuraError.server(code: failure.code, message: failure.message, status: reply.status)
        }
    }

    private var consentJustGranted: Bool {
        guard let grantedAt = consentGrantedAt else { return false }
        return now().timeIntervalSince(grantedAt) < ServiceClient.consentWindow
    }

    private func makeURL(_ service: ServiceName, _ path: String, _ query: [String: String]) throws -> URL {
        guard let base = config.baseURL(service) else {
            throw AuraError.server(code: "bad_config", message: "No address is set for \(service.rawValue).", status: 0)
        }
        var text = base.absoluteString
        while text.hasSuffix("/") {
            text.removeLast()
        }
        text += path
        if !query.isEmpty {
            var pairs: [String] = []
            for key in query.keys.sorted() {
                pairs.append(URLEscape.component(key) + "=" + URLEscape.component(query[key] ?? ""))
            }
            text += "?" + pairs.joined(separator: "&")
        }
        guard let url = URL(string: text) else {
            throw AuraError.server(code: "bad_config", message: "That address isn't valid.", status: 0)
        }
        return url
    }

    private func makeRequest(_ url: URL, _ method: String, _ body: Data?, _ token: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 25
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(TimeZone.current.identifier, forHTTPHeaderField: "X-Time-Zone")
        request.setValue("\(config.platform)/\(config.appVersion)", forHTTPHeaderField: "X-Aura-App")
        if let body = body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    /// Holds a slot only while the call is open, never while waiting to retry.
    private func transmit(_ request: URLRequest) async throws -> (status: Int, data: Data) {
        await limiter.acquire()
        let answer: (Data, URLResponse)
        do {
            try Task.checkCancellation()
            answer = try await session.data(for: request)
        } catch {
            await limiter.release()
            throw NetworkFailure.mapped(error)
        }
        await limiter.release()
        guard let http = answer.1 as? HTTPURLResponse else { throw AuraError.offline }
        return (status: http.statusCode, data: answer.0)
    }
}
