import Foundation
import XCTest
@testable import AuraKit

enum StubAnswer {
    case json(Int, String)
    case fail(URLError.Code)
}

/// One request as the stub saw it. `body` is its JSON object, empty when it had none.
struct StubCall {
    let key: String
    let request: URLRequest
    let body: [String: Any]

    func header(_ name: String) -> String? {
        return request.value(forHTTPHeaderField: name)
    }

    var address: String {
        return request.url?.absoluteString ?? ""
    }

    var query: [String: String] {
        var values: [String: String] = [:]
        guard let url = request.url,
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let items = parts.queryItems else { return values }
        for item in items {
            values[item.name] = item.value ?? ""
        }
        return values
    }

    /// A string one level down in the body, like `AuthParameters.USERNAME`.
    func nested(_ outer: String, _ inner: String) -> String? {
        let object = body[outer] as? [String: Any]
        return object?[inner] as? String
    }
}

/// Answers by route: the path for an API call, the `X-Amz-Target` action for Cognito. A queue's last answer repeats.
final class StubServer: @unchecked Sendable {
    private static let currentLock = NSLock()
    private static var active: StubServer?

    static var current: StubServer? {
        get {
            currentLock.lock()
            defer { currentLock.unlock() }
            return active
        }
        set {
            currentLock.lock()
            defer { currentLock.unlock() }
            active = newValue
        }
    }

    private let lock = NSLock()
    private var queues: [String: [StubAnswer]] = [:]
    private var handlers: [String: (StubCall) -> StubAnswer] = [:]
    private var delays: [String: TimeInterval] = [:]
    private var recorded: [StubCall] = []
    private var open = 0
    private var mostOpen = 0

    func on(_ key: String, _ answers: [StubAnswer], delay: TimeInterval = 0) {
        lock.lock()
        defer { lock.unlock() }
        queues[key] = answers
        delays[key] = delay
    }

    func respond(_ key: String, delay: TimeInterval = 0, _ handler: @escaping (StubCall) -> StubAnswer) {
        lock.lock()
        defer { lock.unlock() }
        handlers[key] = handler
        delays[key] = delay
    }

    func calls(_ key: String) -> [StubCall] {
        lock.lock()
        defer { lock.unlock() }
        return recorded.filter { $0.key == key }
    }

    var allCalls: [StubCall] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    var peakOpen: Int {
        lock.lock()
        defer { lock.unlock() }
        return mostOpen
    }

    fileprivate func take(_ request: URLRequest, _ data: Data) -> (answer: StubAnswer, delay: TimeInterval) {
        let target = request.value(forHTTPHeaderField: "X-Amz-Target")
        let key = target?.components(separatedBy: ".").last ?? (request.url?.path ?? "")
        let object = try? JSONSerialization.jsonObject(with: data)
        let call = StubCall(key: key, request: request, body: (object as? [String: Any]) ?? [:])
        lock.lock()
        defer { lock.unlock() }
        recorded.append(call)
        open += 1
        mostOpen = max(mostOpen, open)
        let delay = delays[key] ?? 0
        if let handler = handlers[key] {
            return (answer: handler(call), delay: delay)
        }
        guard var queue = queues[key], let first = queue.first else {
            return (answer: .json(500, "{\"error\":{\"code\":\"no_stub\",\"message\":\"\(key)\"}}"), delay: 0)
        }
        if queue.count > 1 {
            queue.removeFirst()
            queues[key] = queue
        }
        return (answer: first, delay: delay)
    }

    fileprivate func finished() {
        lock.lock()
        defer { lock.unlock() }
        open -= 1
    }
}

/// Stands in for the network: nothing in these tests can leave the machine.
final class StubURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        guard let server = StubServer.current else {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let reply = server.take(request, StubURLProtocol.bodyData(of: request))
        if reply.delay > 0 {
            DispatchQueue.global().asyncAfter(deadline: .now() + reply.delay) {
                self.deliver(reply.answer, server)
            }
        } else {
            deliver(reply.answer, server)
        }
    }

    override func stopLoading() {}

    private func deliver(_ answer: StubAnswer, _ server: StubServer) {
        server.finished()
        switch answer {
        case .fail(let code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .json(let status, let text):
            guard let url = request.url,
                  let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                                 headerFields: ["Content-Type": "application/json"]) else {
                client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
                return
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(text.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    /// URLSession moves a request's body into a stream before a protocol sees it.
    private static func bodyData(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        let size = 4096
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: size)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: size)
            if count <= 0 { break }
            data.append(contentsOf: buffer[0..<count])
        }
        return data
    }
}

final class SleepLog: @unchecked Sendable {
    private let lock = NSLock()
    private var seconds: [TimeInterval] = []

    func add(_ value: TimeInterval) {
        lock.lock()
        defer { lock.unlock() }
        seconds.append(value)
    }

    var values: [TimeInterval] {
        lock.lock()
        defer { lock.unlock() }
        return seconds
    }
}

/// A token shaped like a JWT, with an unsigned payload.
func makeTestJWT(_ claims: [String: Any]) -> String {
    let data = (try? JSONSerialization.data(withJSONObject: claims)) ?? Data()
    let body = data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
    return "header.\(body).signature"
}

/// The error a call threw, if any.
func caught(_ body: () async throws -> Void) async -> Error? {
    do {
        try await body()
        return nil
    } catch {
        return error
    }
}

func runConcurrently(_ count: Int, _ work: @escaping @Sendable () async throws -> Void) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
        for _ in 0..<count {
            group.addTask {
                try await work()
            }
        }
        try await group.waitForAll()
    }
}

/// A live API and sign-in wired to the stub, with a fixed clock and a sleep that returns at once.
final class TestRig {
    static let now = Date(timeIntervalSince1970: 1_900_000_000)
    static let email = "person@example.com"
    static let idToken = makeTestJWT(["sub": "sub-1", "email": email])

    static var config: AppConfig {
        var services: [String: String] = [:]
        for service in ServiceName.allCases {
            services[service.rawValue] = "https://\(service.rawValue).stub.test"
        }
        return AppConfig(stage: "dev", region: "us-west-1", userPoolId: "us-west-1_stub", clientId: "stub-client",
                         platform: "ios", appVersion: "0.1.0", consentPolicyVersion: "2026-10", services: services)
    }

    static func tokens(expiresIn seconds: TimeInterval = 3600) -> AuthTokens {
        return AuthTokens(idToken: idToken, accessToken: "access-1", refreshToken: "refresh-1",
                          expiresAt: now.addingTimeInterval(seconds))
    }

    /// Cognito's answer to REFRESH_TOKEN_AUTH: it doesn't rotate the refresh token.
    static var refreshAnswer: StubAnswer {
        return .json(200, "{\"AuthenticationResult\":{\"AccessToken\":\"access-2\",\"IdToken\":\"\(idToken)\",\"ExpiresIn\":3600}}")
    }

    /// Cognito's answer once a sign-in is complete.
    static var signedInAnswer: StubAnswer {
        return .json(200, "{\"AuthenticationResult\":{\"AccessToken\":\"access-1\",\"IdToken\":\"\(idToken)\",\"RefreshToken\":\"refresh-1\",\"ExpiresIn\":3600}}")
    }

    static func refused(_ kind: String, _ message: String) -> StubAnswer {
        return .json(400, "{\"__type\":\"\(kind)\",\"message\":\"\(message)\"}")
    }

    let server = StubServer()
    let store: MemoryTokenStore
    let sleeps = SleepLog()
    let auth: CognitoAuth
    let api: LiveAPI

    init(tokens: AuthTokens? = TestRig.tokens(), config: AppConfig = TestRig.config) {
        StubServer.current = server
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let now: @Sendable () -> Date = { TestRig.now }
        let log = sleeps
        let sleep: AuraSleep = { (seconds: TimeInterval) in
            log.add(seconds)
        }
        store = MemoryTokenStore(tokens: tokens)
        auth = CognitoAuth(config: config, store: store, session: session, now: now)
        api = LiveAPI(config: config, tokens: auth, session: session, sleep: sleep, now: now)
    }
}
