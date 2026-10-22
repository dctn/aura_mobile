"""Sign-in against a pool that hides unknown emails (preventUserExistenceErrors). No network."""

from __future__ import annotations

import json

import httpx

from aura_mobile.services.auth import CognitoClient


def client(config, answers):
    calls: list[str] = []

    def handle(request: httpx.Request) -> httpx.Response:
        target = request.headers["X-Amz-Target"].split(".")[-1]
        calls.append(target)
        return answers[target].pop(0)

    return CognitoClient(config, transport=httpx.MockTransport(handle)), calls


def ok(body: dict) -> httpx.Response:
    return httpx.Response(200, content=json.dumps(body).encode())


def refused(kind: str, message: str) -> httpx.Response:
    return httpx.Response(400, content=json.dumps({"__type": kind, "message": message}).encode())


SELECT = {"ChallengeName": "SELECT_CHALLENGE", "Session": "s1", "ChallengeParameters": {}}


def test_existing_account_gets_an_8_digit_code(config):
    c, calls = client(config, {
        "InitiateAuth": [ok({**SELECT, "AvailableChallenges": ["PASSWORD", "EMAIL_OTP"]})],
        "RespondToAuthChallenge": [ok({"ChallengeName": "EMAIL_OTP", "Session": "s2",
                                       "ChallengeParameters": {"CODE_DELIVERY_DESTINATION": "t***@e***"}})],
        "ResendConfirmationCode": [refused("InvalidParameterException", "User is already confirmed.")],
    })
    step = c.start("tester@example.com")
    assert step.kind == "sign_in" and step.code_length == 8 and step.session == "s2"
    assert not step.also_confirm
    assert calls == ["InitiateAuth", "RespondToAuthChallenge", "ResendConfirmationCode"]


def test_hidden_status_asks_for_both_codes_and_takes_a_6_digit_one(config):
    c, calls = client(config, {
        "InitiateAuth": [ok({**SELECT, "AvailableChallenges": ["EMAIL_OTP"]}),
                         ok({"AuthenticationResult": {"AccessToken": "a", "IdToken": "i", "RefreshToken": "r",
                                                      "ExpiresIn": 3600}})],
        "RespondToAuthChallenge": [ok({"ChallengeName": "EMAIL_OTP", "Session": "s2"})],
        "ResendConfirmationCode": [ok({"CodeDeliveryDetails": {"Destination": "d***@g***"}})],
        "ConfirmSignUp": [ok({"Session": "cs"})],
    })
    step = c.start("pending@example.com")
    assert step.kind == "sign_in" and step.also_confirm
    try:
        c.finish(step, "123456")
    except Exception:
        pass  # fake tokens may not parse; the route taken is what matters
    assert calls[3:5] == ["ConfirmSignUp", "InitiateAuth"]


def test_hidden_unknown_email_signs_up(config):
    c, calls = client(config, {
        "InitiateAuth": [ok({**SELECT, "AvailableChallenges": ["PASSWORD_SRP", "PASSWORD"]})],
        "SignUp": [ok({"Session": "su", "CodeDeliveryDetails": {"Destination": "n***@e***"}})],
    })
    step = c.start("new@example.com")
    assert step.kind == "confirm_sign_up" and step.code_length == 6
    assert calls == ["InitiateAuth", "SignUp"]


def test_refused_email_otp_choice_signs_up(config):
    c, calls = client(config, {
        "InitiateAuth": [ok(SELECT)],
        "RespondToAuthChallenge": [refused("InvalidParameterException", "The selected challenge is not available.")],
        "SignUp": [ok({"Session": "su"})],
    })
    assert c.start("new@example.com").is_new_account
    assert calls[-1] == "SignUp"


def test_hidden_unconfirmed_account_resends_confirmation(config):
    c, calls = client(config, {
        "InitiateAuth": [ok({**SELECT, "AvailableChallenges": ["PASSWORD"]})],
        "SignUp": [refused("UsernameExistsException", "User already exists")],
        "ResendConfirmationCode": [ok({"CodeDeliveryDetails": {"Destination": "u***@e***"}})],
    })
    step = c.start("pending@example.com")
    assert step.kind == "confirm_sign_up"
    assert calls == ["InitiateAuth", "SignUp", "ResendConfirmationCode"]
