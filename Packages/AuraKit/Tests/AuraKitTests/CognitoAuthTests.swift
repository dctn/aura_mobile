import Foundation
import XCTest
@testable import AuraKit

private let otpChallenge = """
{"ChallengeName":"EMAIL_OTP","Session":"otp-session","ChallengeParameters":{"CODE_DELIVERY_DESTINATION":"p***@example.com"}}
"""
private let selectWithOTP = """
{"ChallengeName":"SELECT_CHALLENGE","Session":"select-session","AvailableChallenges":["EMAIL_OTP","PASSWORD"]}
"""
private let selectWithoutOTP = """
{"ChallengeName":"SELECT_CHALLENGE","Session":"select-session","AvailableChallenges":["PASSWORD","PASSWORD_SRP"]}
"""
private let delivered = """
{"UserConfirmed":false,"UserSub":"sub-1","Session":"signup-session","CodeDeliveryDetails":{"Destination":"p***@example.com","DeliveryMedium":"EMAIL","AttributeName":"email"}}
"""
private let deliveredNoSession = """
{"CodeDeliveryDetails":{"Destination":"p***@example.com","DeliveryMedium":"EMAIL","AttributeName":"email"}}
"""
private let alreadyConfirmed = TestRig.refused("InvalidParameterException", "User is already confirmed.")

final class CognitoSignInTests: XCTestCase {
    func testCallsCognitoInTheRightRegionWithItsHeaders() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, otpChallenge)])
        rig.server.on("ResendConfirmationCode", [alreadyConfirmed])
        _ = try await rig.auth.requestCode(email: TestRig.email)
        let call = try XCTUnwrap(rig.server.calls("InitiateAuth").first)
        XCTAssertEqual(call.request.url?.host, "cognito-idp.us-west-1.amazonaws.com")
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertEqual(call.header("Content-Type"), "application/x-amz-json-1.1")
        XCTAssertEqual(call.header("X-Amz-Target"), "AWSCognitoIdentityProviderService.InitiateAuth")
        XCTAssertNil(call.header("Authorization"))
    }

    func testExistingAccountGetsAnEightDigitSignIn() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, otpChallenge)])
        rig.server.on("ResendConfirmationCode", [alreadyConfirmed])
        let step = try await rig.auth.requestCode(email: "  Person@Example.COM ")
        XCTAssertEqual(step.kind, AuthStep.Kind.signIn)
        XCTAssertEqual(step.email, TestRig.email)
        XCTAssertEqual(step.session, "otp-session")
        XCTAssertEqual(step.codeLength, 8)
        XCTAssertFalse(step.alsoConfirm)
        XCTAssertEqual(step.destination, "p***@example.com")
        let first = try XCTUnwrap(rig.server.calls("InitiateAuth").first)
        XCTAssertEqual(first.body["AuthFlow"] as? String, "USER_AUTH")
        XCTAssertEqual(first.body["ClientId"] as? String, "stub-client")
        XCTAssertEqual(first.nested("AuthParameters", "USERNAME"), TestRig.email)
        XCTAssertEqual(first.nested("AuthParameters", "PREFERRED_CHALLENGE"), "EMAIL_OTP")

        rig.server.on("RespondToAuthChallenge", [TestRig.signedInAnswer])
        let state = try await rig.auth.finish(step: step, code: "1234 5678")
        XCTAssertEqual(state, AuthState.signedIn(email: TestRig.email))
        let answer = try XCTUnwrap(rig.server.calls("RespondToAuthChallenge").first)
        XCTAssertEqual(answer.body["ChallengeName"] as? String, "EMAIL_OTP")
        XCTAssertEqual(answer.body["Session"] as? String, "otp-session")
        XCTAssertEqual(answer.nested("ChallengeResponses", "USERNAME"), TestRig.email)
        XCTAssertEqual(answer.nested("ChallengeResponses", "EMAIL_OTP_CODE"), "12345678")
        XCTAssertEqual(rig.store.tokens?.refreshToken, "refresh-1")
    }

    func testSelectChallengeOfferingEmailCodeIsAnswered() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, selectWithOTP)])
        rig.server.on("RespondToAuthChallenge", [.json(200, otpChallenge)])
        rig.server.on("ResendConfirmationCode", [alreadyConfirmed])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        XCTAssertEqual(step.kind, AuthStep.Kind.signIn)
        XCTAssertEqual(step.session, "otp-session")
        let pick = try XCTUnwrap(rig.server.calls("RespondToAuthChallenge").first)
        XCTAssertEqual(pick.body["ChallengeName"] as? String, "SELECT_CHALLENGE")
        XCTAssertEqual(pick.body["Session"] as? String, "select-session")
        XCTAssertEqual(pick.nested("ChallengeResponses", "ANSWER"), "EMAIL_OTP")
    }

    func testNoEmailCodeOfferedMeansSignUpWithSixDigits() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, selectWithoutOTP)])
        rig.server.on("SignUp", [.json(200, delivered)])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        XCTAssertEqual(step.kind, AuthStep.Kind.confirmSignUp)
        XCTAssertEqual(step.codeLength, 6)
        XCTAssertEqual(step.session, "signup-session")
        XCTAssertEqual(step.destination, "p***@example.com")
        XCTAssertEqual(rig.server.calls("RespondToAuthChallenge").count, 0)
        let signUp = try XCTUnwrap(rig.server.calls("SignUp").first)
        XCTAssertNil(signUp.body["Password"])
        XCTAssertEqual(signUp.body["Username"] as? String, TestRig.email)

        rig.server.on("ConfirmSignUp", [.json(200, "{\"Session\":\"confirm-session\"}")])
        rig.server.on("InitiateAuth", [TestRig.signedInAnswer])
        let state = try await rig.auth.finish(step: step, code: "123456")
        XCTAssertEqual(state, AuthState.signedIn(email: TestRig.email))
        let confirm = try XCTUnwrap(rig.server.calls("ConfirmSignUp").first)
        XCTAssertEqual(confirm.body["ConfirmationCode"] as? String, "123456")
        let signIn = try XCTUnwrap(rig.server.calls("InitiateAuth").last)
        XCTAssertEqual(signIn.body["Session"] as? String, "confirm-session")
        XCTAssertEqual(signIn.body["AuthFlow"] as? String, "USER_AUTH")
        XCTAssertEqual(signIn.nested("AuthParameters", "USERNAME"), TestRig.email)
    }

    func testChallengeNotAvailableMeansSignUp() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, selectWithOTP)])
        rig.server.on("RespondToAuthChallenge", [TestRig.refused("NotAuthorizedException", "The challenge is not available for this user.")])
        rig.server.on("SignUp", [.json(200, delivered)])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        XCTAssertEqual(step.kind, AuthStep.Kind.confirmSignUp)
        XCTAssertEqual(step.codeLength, 6)
    }

    func testUserNotFoundMeansSignUp() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [TestRig.refused("com.amazon#UserNotFoundException", "User does not exist.")])
        rig.server.on("SignUp", [.json(200, delivered)])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        XCTAssertEqual(step.kind, AuthStep.Kind.confirmSignUp)
    }

    func testExistingUnconfirmedAccountGetsANewConfirmationCode() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, selectWithoutOTP)])
        rig.server.on("SignUp", [TestRig.refused("UsernameExistsException", "User already exists")])
        rig.server.on("ResendConfirmationCode", [.json(200, deliveredNoSession)])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        XCTAssertEqual(step.kind, AuthStep.Kind.confirmSignUp)
        XCTAssertNil(step.session)
        XCTAssertEqual(rig.server.calls("ResendConfirmationCode").count, 1)
    }

    func testPoolInsistingOnAPasswordGetsARandomOne() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, selectWithoutOTP)])
        rig.server.on("SignUp", [TestRig.refused("InvalidPasswordException", "Password did not conform"), .json(200, delivered)])
        _ = try await rig.auth.requestCode(email: TestRig.email)
        let calls = rig.server.calls("SignUp")
        XCTAssertEqual(calls.count, 2)
        XCTAssertNil(calls[0].body["Password"])
        let password = calls[1].body["Password"] as? String ?? ""
        XCTAssertTrue(password.hasPrefix("Cl4!"))
        XCTAssertGreaterThan(password.count, 20)
    }

    func testBothCodesStepTakesASixDigitConfirmation() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, otpChallenge)])
        rig.server.on("ResendConfirmationCode", [.json(200, deliveredNoSession)])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        XCTAssertEqual(step.kind, AuthStep.Kind.signIn)
        XCTAssertTrue(step.alsoConfirm)

        rig.server.on("ConfirmSignUp", [.json(200, "{\"Session\":\"confirm-session\"}")])
        rig.server.on("InitiateAuth", [TestRig.signedInAnswer])
        let state = try await rig.auth.finish(step: step, code: "123456")
        XCTAssertEqual(state, AuthState.signedIn(email: TestRig.email))
        XCTAssertEqual(rig.server.calls("ConfirmSignUp").count, 1)
        XCTAssertEqual(rig.server.calls("RespondToAuthChallenge").count, 0)
    }

    func testBothCodesStepTakesAnEightDigitSignIn() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, otpChallenge)])
        rig.server.on("ResendConfirmationCode", [.json(200, deliveredNoSession)])
        let step = try await rig.auth.requestCode(email: TestRig.email)
        rig.server.on("RespondToAuthChallenge", [TestRig.signedInAnswer])
        let state = try await rig.auth.finish(step: step, code: "12345678")
        XCTAssertEqual(state, AuthState.signedIn(email: TestRig.email))
        XCTAssertEqual(rig.server.calls("ConfirmSignUp").count, 0)
        XCTAssertEqual(rig.server.calls("RespondToAuthChallenge").count, 1)
    }

    func testWrongCodeIsPutIntoWords() async {
        let rig = TestRig(tokens: nil)
        rig.server.on("RespondToAuthChallenge", [TestRig.refused("CodeMismatchException", "Invalid code received")])
        let step = AuthStep(kind: .signIn, email: TestRig.email, session: "otp-session", codeLength: 8)
        let error = await caught { _ = try await rig.auth.finish(step: step, code: "00000000") }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "CodeMismatchException",
                                                             message: "That code isn't right. Check the email and try again.",
                                                             status: 400))
        XCTAssertNil(rig.store.tokens)
    }

    func testBadEmailNeverReachesCognito() async {
        let rig = TestRig(tokens: nil)
        let error = await caught { _ = try await rig.auth.requestCode(email: "not an email") }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "invalid_email", message: "Enter a valid email address.", status: 0))
        XCTAssertEqual(rig.server.allCalls.count, 0)
    }

    func testOfflineDuringSignIn() async {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.fail(.notConnectedToInternet)])
        let error = await caught { _ = try await rig.auth.requestCode(email: TestRig.email) }
        XCTAssertEqual(error as? AuraError, AuraError.offline)
    }

    func testConfirmedWithoutASessionNeedsASignInCode() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("ConfirmSignUp", [.json(200, "{}")])
        let step = AuthStep(kind: .confirmSignUp, email: TestRig.email, session: nil, codeLength: 6)
        let error = await caught { _ = try await rig.auth.finish(step: step, code: "123456") }
        guard case .server(let code, _, _)? = error as? AuraError else {
            XCTFail("Expected a server error")
            return
        }
        XCTAssertEqual(code, "confirmed_without_session")

        rig.server.on("InitiateAuth", [.json(200, otpChallenge)])
        let next = try await rig.auth.resend(step: step)
        XCTAssertEqual(next.kind, AuthStep.Kind.signIn)
        XCTAssertEqual(next.codeLength, 8)
        XCTAssertEqual(rig.server.calls("ResendConfirmationCode").count, 0)
    }

    func testAlreadyConfirmedOnResendSendsASignInCode() async throws {
        let rig = TestRig(tokens: nil)
        rig.server.on("ResendConfirmationCode", [alreadyConfirmed])
        rig.server.on("InitiateAuth", [.json(200, otpChallenge)])
        let step = AuthStep(kind: .confirmSignUp, email: TestRig.email, session: nil, codeLength: 6)
        let next = try await rig.auth.resend(step: step)
        XCTAssertEqual(next.kind, AuthStep.Kind.signIn)
    }

    func testSignInStepWithAnUnknownChallengeIsUnsupported() async {
        let rig = TestRig(tokens: nil)
        rig.server.on("InitiateAuth", [.json(200, "{\"ChallengeName\":\"MFA_SETUP\",\"Session\":\"s\"}")])
        let error = await caught { _ = try await rig.auth.requestCode(email: TestRig.email) }
        guard case .server(let code, let message, _)? = error as? AuraError else {
            XCTFail("Expected a server error")
            return
        }
        XCTAssertEqual(code, "unsupported_step")
        XCTAssertTrue(message.contains("MFA_SETUP"))
    }
}

final class CognitoSessionTests: XCTestCase {
    func testRestoreWithASavedSession() async {
        let rig = TestRig()
        let state = await rig.auth.restore()
        XCTAssertEqual(state, AuthState.signedIn(email: TestRig.email))
        XCTAssertEqual(rig.server.allCalls.count, 0)
    }

    func testRestoreWithNothingSaved() async {
        let rig = TestRig(tokens: nil)
        let state = await rig.auth.restore()
        XCTAssertEqual(state, AuthState.signedOut)
    }

    func testRestoreIgnoresTokensWithoutAnIdentity() async {
        let unusable = AuthTokens(idToken: "junk", accessToken: "a", refreshToken: "r", expiresAt: TestRig.now.addingTimeInterval(3600))
        let rig = TestRig(tokens: unusable)
        let state = await rig.auth.restore()
        XCTAssertEqual(state, AuthState.signedOut)
    }

    func testSignOutRevokesAndClears() async throws {
        let rig = TestRig()
        rig.server.on("RevokeToken", [.json(200, "{}")])
        await rig.auth.signOut()
        XCTAssertNil(rig.store.tokens)
        let revoke = try XCTUnwrap(rig.server.calls("RevokeToken").first)
        XCTAssertEqual(revoke.body["Token"] as? String, "refresh-1")
        XCTAssertEqual(revoke.body["ClientId"] as? String, "stub-client")
    }

    func testSignOutWorksOffline() async {
        let rig = TestRig()
        rig.server.on("RevokeToken", [.fail(.notConnectedToInternet)])
        await rig.auth.signOut()
        XCTAssertNil(rig.store.tokens)
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.signedOut)
    }

    func testSignOutDuringARefreshDoesNotBringTheSessionBack() async throws {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 10))
        rig.server.on("InitiateAuth", [TestRig.refreshAnswer], delay: 0.5)
        rig.server.on("RevokeToken", [.json(200, "{}")])
        rig.server.on("/v1/tasks", [.json(200, "{\"tasks\":[],\"cursor\":null}")])
        let api = rig.api
        let call = Task<Void, Error> {
            _ = try await api.tasks()
        }
        // Long enough for the refresh to be in flight, shorter than its answer.
        try await Task.sleep(nanoseconds: 30_000_000)
        await rig.auth.signOut()
        let result = await call.result
        if case .success = result {
            XCTFail("The call should have failed")
        }
        XCTAssertNil(rig.store.tokens)
    }

    func testOtherRefusalKeepsTheSession() async {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 10))
        rig.server.on("InitiateAuth", [TestRig.refused("TooManyRequestsException", "Rate exceeded")])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "TooManyRequestsException",
                                                             message: "Too many attempts. Wait a minute, then try again.",
                                                             status: 400))
        XCTAssertNotNil(rig.store.tokens)
    }

    func testTokenStateFromClaims() {
        let access = makeTestJWT(["exp": TestRig.now.timeIntervalSince1970 + 600])
        let fromToken = AuthTokens.issued(accessToken: access, idToken: TestRig.idToken, refreshToken: "r", expiresIn: 3600, now: TestRig.now)
        XCTAssertEqual(fromToken.expiresAt, TestRig.now.addingTimeInterval(600))
        XCTAssertEqual(fromToken.sub, "sub-1")
        XCTAssertEqual(fromToken.email, TestRig.email)
        let fromAnswer = AuthTokens.issued(accessToken: "opaque", idToken: "junk", refreshToken: "r", expiresIn: 60, now: TestRig.now)
        XCTAssertEqual(fromAnswer.expiresAt, TestRig.now.addingTimeInterval(60))
        XCTAssertEqual(fromAnswer.sub, "")
        XCTAssertNil(fromAnswer.email)
    }
}
