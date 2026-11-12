---
name: aws-docs-repo-compromise
description: aws_docs GitHub repo (dctn/aura-aws-backend) got malicious workflows pushed to main on 19 Oct and 21 Oct 2026; the 21 Oct push also redeployed dev Identity from main
metadata:
  node_type: memory
  type: project
  originSessionId: 2352f890-89f8-4a5a-874e-47a97a8fb600
  modified: 2026-10-21T14:54:26.714Z
---

Two malicious workflows have been pushed to `main` of dctn/aura-aws-backend (the `aws_docs` folder):
- 19 Oct 2026: `f190465` "Add code review workflow", by "ci". The user removed it in `f288683`.
- 21 Oct 2026 07:04 IST: `ded90c1` "Add Github Actions Security workflow", authored as IBT Aura <<project Gmail (SES sender)>>.
  - It posts `vars.AWS_DEPLOY_ROLE_ARN` and `AWS_DIFF_ROLE_ARN` to http://193.32.204.199:8080.
  - It also tries an OIDC AssumeRoleWithWebIdentity against every role ARN found in env vars.
  - CloudTrail in us-east-1 shows AccessDenied attempts from ibtaura/Aura-Clarus-Desktop-app on 20 Oct 18:29Z and 21 Oct 01:34Z, so that repo was hit too. The deploy role trusts only dctn/aura-aws-backend main.

Side effect: that push ran ci.yml `deploy-dev` (01:35Z, 21 Oct) from `main`, which lacks the mobile-features branch. Dev Identity lost the consent, exports, lists and avatar routes and the healthGoals profile field. Health Data then refuses samples, check-ins and logs, because no consent can be read.

**Why:** this explains broken dev routes, and the account or token behind the "IBT Aura" commits is likely compromised.
**How to apply:** when dev routes go missing, check `aws apigatewayv2 get-routes` against the branch, and check `origin/main` for workflows that were not reviewed. Redeploying needs the user's written AWS approval. See [[aws-project-memory]] and [[mobile-features-build]].
