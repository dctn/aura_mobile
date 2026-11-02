import Foundation

/// Builds the real backend and sign-in. The app calls this from `AppModel.make()`.
public enum LiveServices {
    public static func make(config: AppConfig, store: any TokenStore) -> (api: any AuraAPI, auth: any AuthService) {
        // Ephemeral so no health data is cached on disk.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25
        let session = URLSession(configuration: configuration)
        let auth = CognitoAuth(config: config, store: store, session: session)
        let api = LiveAPI(config: config, tokens: auth, session: session, sleep: ServiceClient.realSleep, now: { Date() })
        return (api: api, auth: auth)
    }

    /// Reads App/Resources/aura-dev.json and keeps tokens in the Keychain. Refuses prod and ap-south-1.
    public static func make(configData: Data) throws -> (api: any AuraAPI, auth: any AuthService) {
        let config = try JSONDecoder().decode(AppConfig.self, from: configData)
        if config.stage == "prod" || config.region == "ap-south-1" {
            throw AuraError.server(code: "bad_config", message: "This build only runs against a dev stage.", status: 0)
        }
        return make(config: config, store: KeychainTokenStore(stage: config.stage))
    }
}
