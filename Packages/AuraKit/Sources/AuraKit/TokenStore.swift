import Foundation
import Security

/// What Cognito issued. Only ever written to a `TokenStore`; never log any of it.
public struct AuthTokens: Codable, Sendable, Equatable {
    public var idToken: String
    public var accessToken: String
    public var refreshToken: String
    /// When the access token stops being accepted.
    public var expiresAt: Date

    public init(idToken: String, accessToken: String, refreshToken: String, expiresAt: Date) {
        self.idToken = idToken
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresAt = expiresAt
    }

    /// The Cognito `sub`, from the id token. Empty when the token doesn't carry one.
    var sub: String {
        return (JWT.claims(idToken)["sub"] as? String) ?? ""
    }

    var email: String? {
        guard let email = JWT.claims(idToken)["email"] as? String, !email.isEmpty else { return nil }
        return email.lowercased()
    }

    /// Builds tokens from Cognito's `AuthenticationResult`: the access token's own expiry, else `ExpiresIn`, else an hour.
    static func issued(accessToken: String, idToken: String, refreshToken: String, expiresIn: Any?, now: Date) -> AuthTokens {
        let expiry: Date
        if let fromToken = JWT.expiry(accessToken) {
            expiry = fromToken
        } else if let seconds = expiresIn as? Int, seconds > 0 {
            expiry = now.addingTimeInterval(TimeInterval(seconds))
        } else {
            expiry = now.addingTimeInterval(3600)
        }
        return AuthTokens(idToken: idToken, accessToken: accessToken, refreshToken: refreshToken, expiresAt: expiry)
    }
}

/// Reads a JWT's payload without checking it: the API checks the signature, the app only reads its own identity and expiry.
enum JWT {
    static func claims(_ token: String) -> [String: Any] {
        let parts = token.components(separatedBy: ".")
        guard parts.count >= 2 else { return [:] }
        var body = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while body.count % 4 != 0 {
            body += "="
        }
        guard let data = Data(base64Encoded: body),
              let object = try? JSONSerialization.jsonObject(with: data),
              let claims = object as? [String: Any] else { return [:] }
        return claims
    }

    static func expiry(_ token: String) -> Date? {
        guard let seconds = claims(token)["exp"] as? Double else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }
}

public protocol TokenStore: Sendable {
    func load() throws -> AuthTokens?
    func save(_ tokens: AuthTokens) throws
    func clear() throws
}

public struct KeychainError: Error, Equatable {
    public let status: OSStatus
}

/// One Keychain item per stage, so a dev build never reads another stage's session.
public struct KeychainTokenStore: TokenStore {
    private let service: String
    private let account = "session"

    public init(stage: String) {
        service = "info.ibtaura.aura.session.\(stage)"
    }

    private var itemQuery: [String: Any] {
        return [kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account]
    }

    public func load() throws -> AuthTokens? {
        var query = itemQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        guard let data = found as? Data else { return nil }
        // An item this version can't read counts as signed out.
        return try? JSONDecoder().decode(AuthTokens.self, from: data)
    }

    public func save(_ tokens: AuthTokens) throws {
        let data = try JSONEncoder().encode(tokens)
        let attributes: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let updated = SecItemUpdate(itemQuery as CFDictionary, attributes as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw KeychainError(status: updated) }
        var item = itemQuery
        for (key, value) in attributes {
            item[key] = value
        }
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainError(status: added) }
    }

    public func clear() throws {
        let status = SecItemDelete(itemQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }
}

/// For tests.
public final class MemoryTokenStore: TokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var held: AuthTokens?

    public init(tokens: AuthTokens? = nil) {
        held = tokens
    }

    public var tokens: AuthTokens? {
        lock.lock()
        defer { lock.unlock() }
        return held
    }

    public func load() throws -> AuthTokens? {
        return tokens
    }

    public func save(_ tokens: AuthTokens) throws {
        lock.lock()
        defer { lock.unlock() }
        held = tokens
    }

    public func clear() throws {
        lock.lock()
        defer { lock.unlock() }
        held = nil
    }
}
