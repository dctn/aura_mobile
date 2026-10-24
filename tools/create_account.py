"""Make the demo website's shared account, once. Dev us-west-1 only.

What it does (the AWS writes the user approved for the demo website):

1. Cognito ``AdminCreateUser`` in the dev pool: ``aura-demo-<6 hex>@example.com``, ``email_verified``, no mail sent.
2. ``AdminSetUserPassword --permanent``: a random 32-character password, so the account signs in with no code.
3. Secrets Manager ``PutSecretValue`` on ``aura/dev/demo-site`` (made by the aura-dev-demo-site stack):
   ``{"email", "password"}``; the stack's Lambda reads it.
4. The same pair in Windows Credential Manager ("AuraMobileDemo", ``aura.demo-website.email`` / ``.password``) for
   ``seed.py``.

It prints only the email. The password exists in memory, in the secret and in Credential Manager, nowhere else: it is
never printed, logged, written to a file or passed on a command line (boto3 gets the CLI's short-lived credentials from
``aws configure export-credentials``). If the secret already holds an account, it stops and says which.

    uv run python create_account.py
"""

from __future__ import annotations

import json
import secrets
import subprocess
import sys

import boto3
from aura_mobile.services.secrets import set_secret

REGION = "us-west-1"
POOL = "us-west-1_KSpdUo5a7"
SECRET_ID = "aura/dev/demo-site"
AWS_CLI = r"C:\Program Files\Amazon\AWSCLIV2\aws.exe"
EMAIL_KEY = "aura.demo-website.email"
PASSWORD_KEY = "aura.demo-website.password"


def session() -> boto3.Session:
    """A boto3 session on the CLI's current sign-in (short-lived keys, held in memory only)."""
    out = subprocess.run([AWS_CLI, "configure", "export-credentials", "--format", "process"],
                         capture_output=True, text=True, check=True)
    creds = json.loads(out.stdout)
    return boto3.Session(aws_access_key_id=creds["AccessKeyId"], aws_secret_access_key=creds["SecretAccessKey"],
                         aws_session_token=creds.get("SessionToken"), region_name=REGION)


def existing_email(sm) -> str:
    value = json.loads(sm.get_secret_value(SecretId=SECRET_ID)["SecretString"])
    return str(value.get("email") or "")


def main() -> int:
    aws = session()
    sm = aws.client("secretsmanager")
    if email := existing_email(sm):
        print(f"The demo account already exists: {email}. Nothing changed.")
        return 0
    email = f"aura-demo-{secrets.token_hex(3)}@example.com"
    password = secrets.token_urlsafe(24)[:32]
    idp = aws.client("cognito-idp")
    idp.admin_create_user(UserPoolId=POOL, Username=email, MessageAction="SUPPRESS",
                          UserAttributes=[{"Name": "email", "Value": email},
                                          {"Name": "email_verified", "Value": "true"}])
    idp.admin_set_user_password(UserPoolId=POOL, Username=email, Password=password, Permanent=True)
    sm.put_secret_value(SecretId=SECRET_ID, SecretString=json.dumps({"email": email, "password": password}))
    saved = set_secret(EMAIL_KEY, email) and set_secret(PASSWORD_KEY, password)
    print(f"Demo account ready: {email}")
    if not saved:
        print("Credential Manager refused the copy; seed.py can't sign in until it's saved.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
