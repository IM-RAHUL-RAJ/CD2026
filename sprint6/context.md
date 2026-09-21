Description

Acceptance Criteria: 
  
 * npm ci, npm run build and npm test all succeed on a machine that has never seen your code, from a committed lock file. 
 * The service answers on the port the contract fixes, builds from a multi-stage Dockerfile, and joins your team's local orchestration. 
  
 Tasks: 
  
 Set the project up on Node 20 or later in TypeScript, with sources under a directory the team chooses and specs beside the code they cover. 
  
 Read JWT_SECRET, the database connection and every value that differs between a laptop and a container from the environment at runtime. 
  
 Write the Dockerfile and the orchestration entry, on port 3000 as infra/README.md reserves it.

 Acceptance Criteria: 
  
 * The four operations answer at the paths, verbs, status codes and bodies contracts/auth-api.yaml fixes, with the documented failures. 
 * Every failure leaves in the platform envelope and nothing else. 
  
 Tasks: 
  
 Implement registration, login, refresh and the protected profile route against the contract. 
  
 Issue no tokens on registration; an unauthenticated route that mints a session is an authentication bypass as soon as it has its first defect. 
  
 Create no trading account on registration; accounts are owned by the Sprint 3 schema. 
  
 Validate inputs with class-validator and class-transformer and answer VAL-422 on a field failure.

 Description

Acceptance Criteria: 
  
 * Passwords are stored with argon2id, or bcrypt at cost 12 or above, and plaintext is never stored. 
 * No password reaches a log by a direct route, and the cost factor is a decision the team can defend. 
  
 Tasks: 
  
 Build the credential store as a migration or bootstrap, plus the repository that reads it. 
  
 Choose cost parameters that make one verification take on the order of a tenth of a second on the hardware you deploy to, and say why. 
  
 Log through one logger that redacts by key name at any depth, and never hand a whole request body to a log call. 
  
 Unit Test Execution Paths: 
  
 # correct password verifies 
 # incorrect password fails verification 
 # a general-purpose digest such as MD5 or SHA is not used 
  
 Notes: 
  
 * A general-purpose hash is built to be fast, and fast is the one property a password hash must not have. 
 * Too low a cost gives an offline attacker the same speedup you got; too high and your login route is the cheapest denial-of-service target in the platform. 
 * The indirect routes to a log, such as an error object serialised whole or a DTO printed in a stack trace, are what the review asks about.

 Team 3 - Refresh Token Issuance and Rotation

To Do



Description

Acceptance Criteria: 
  
 * Every refresh issues a new refresh token and the new one works. 
 * Where revocation of the presented token is built, a second presentation answers AUTH-401; where it is not, the decision and its residual risk are written in the security review. 
  
 Tasks: 
  
 Store a hash of the refresh token rather than the token, so that read access to your database is not session takeover. 
  
 Issue a new refresh token on every refresh. 
  
 Decide whether to revoke the presented token, then build it or document it, and say which in the security review. 
  
 Unit Test Execution Paths: 
  
 # refresh returns a new access token and a new refresh token 
 # the newly issued refresh token works 
 # the declared revocation behaviour holds for a token that has already been exchanged 
  
 Notes: 
  
 * Reissue alone defends against a stolen token used once. What is missing without revocation is that the old value still works, so both parties hold live sessions and nothing in the service can tell there are two of them.

 Description

Acceptance Criteria: 
  
 * An unknown username and a wrong password return the same status, the same body and comparable timing. 
 * A login throttle exists with a documented cooldown and attempt count, and neither path returns early. 
  
 Tasks: 
  
 Return AUTH-401 with one message for an unknown user, a wrong password, an expired token, a wrongly signed token and a malformed header. 
  
 Where the username is not found, verify the supplied password against a fixed dummy hash of the same algorithm and parameters, discard the result and return the same failure. 
  
 Build the login throttle and record its cooldown and its attempt count in the sprint README. 
  
 Unit Test Execution Paths: 
  
 # an unknown user and a wrong password return the same status and body 
 # neither path returns before doing comparable work 
 # the throttle limits repeated failed attempts from one caller 
  
 Notes: 
  
 * An attacker with a username list and a stopwatch reads your customer base off the response times without guessing a password. 
 * A throttle limits how fast an attacker can use an oracle you left open. It does not close it.

 Team 3 - OpenAPI Served by the Running Service

To Do



Description

Acceptance Criteria: 
  
 * The running service publishes its own OpenAPI document, generated from the decorators on the controller and the DTOs, describing the four routes. 
 * The human page and the JSON document answer at paths recorded in the sprint README. 
  
 Tasks: 
  
 Generate the document from your code rather than maintaining a YAML file by hand beside it. 
  
 Record both paths in the sprint README. 
  
 Notes: 
  
 * This does not replace contracts/auth-api.yaml. It is the evidence that your code still matches it.

 Team 3 - Access Token Issuance and the Exact Claim Set

To Do



Description

Acceptance Criteria: 
  
 * The access token is signed HS256 with JWT_SECRET and carries exactly the claim set the contract fixes, plus the issuer the contract defines. 
 * Anything outside that set is a finding, not a bonus, and expiry is set as the contract states. 
  
 Tasks: 
  
 Build and sign the token, setting the issued-at and expiry claims as the contract requires. 
  
 Carry the numeric trading account key in the account claim, which is what the Trade REST API compares against the account in the request. 
  
 Set your own issuer value so that a team can decode a token during the cutover and see which implementation signed it, and do not require a particular issuer value in any consumer. 
  
 Unit Test Execution Paths: 
  
 # a token is issued with the contract claims and expiry 
 # the token signature verifies with the service key 
 # no claim outside the contract set is present 
  
 Notes: 
  
 * The payload is base64 and not encryption. An email address in the payload is an email address published to every holder of the token. 
 * Removing a claim after Sprint 9 has generated a client from it is not a configuration change.

 Team 3 - Guard and Token Verification on the Protected Route

To Do



Description

Acceptance Criteria: 
  
 * The protected route is guarded, and an expired, tampered or malformed token is refused with AUTH-401. 
 * Jest covers the guard, including a passing test for the expired-token path and one for the wrong-signature path. 
  
 Tasks: 
  
 Implement the guard and a decorator that extracts the authenticated user from the verified token. 
  
 Build the expired case by signing a genuine token with a past expiry, not by corrupting the payload, which the signature check refuses first. 
  
 Build the wrong-signature case by signing a genuine, unexpired token with a different key. 
  
 Unit Test Execution Paths: 
  
 # a valid token is accepted 
 # an expired token is refused 
 # a token with a wrong signature is refused before any claim is read 
 # a malformed header is refused 
  
 Notes: 
  
 * A guard that checks the signature and forgets the clock accepts every token it ever issued, for ever.

 Team 3 - OWASP Security Review, Committed

To Do



Description

Acceptance Criteria: 
  
 * The security review is committed, differs from the template, and carries a written finding and a disposition for every category. 
 * A finding of none names what was checked, and a disposition of accepted states the residual risk and why the team is carrying it. 
  
 Tasks: 
  
 Copy security-review/TEMPLATE.md, name your copy in the sprint README, and fill it in as you build rather than on the last evening. 
  
 Cover broken authentication and broken access control, token leakage, replay and weak secrets. 
  
 Where revocation of the presented refresh token was not built, write that decision and its risk into the review. 
  
 Notes: 
  
 * Every category needs both cells filled, and whether what is in them is true is read by your instructor.

 Team 3 - Adopt the Real Auth Service with a Configuration Change Only

To Do



Description

Acceptance Criteria: 
  
 * At least one Trade REST API route is protected end to end by a token from this service, with no Java changed in that service. 
 * A token signed with a key the platform should not trust is refused. 
  
 Tasks: 
  
 Add your service to the team's local orchestration on port 3000, the port infra/README.md reserves for it. 
  
 Read the same JWT_SECRET the Trade REST API verifies with, passed through your orchestration from the root environment file. 
  
 Point the Trade REST API's issuer setting at your issuer through its environment, if it pins one. 
  
 Decide whether to keep or rotate the development JWT_SECRET published in .env.example now that a real issuer signs with it, and record the choice in the security review. 
  
 Integration Test 
  
 Obtain a token from the running Auth service, call a protected Trade REST API route with it, and confirm refusal with no token and with a token signed by a key the platform should not trust. 
  
 Notes: 
  
 * The criterion is read with git diff at the design review; no script can tell a configuration change from a code change that looks like one. 
 * If your Trade REST API needs a code change, something in it is coupled to your Sprint 6 test fixture rather than to the token contract: a hard-coded issuer, a claim read with a name that fixture happened to use, or a verifier that decoded before it verified.