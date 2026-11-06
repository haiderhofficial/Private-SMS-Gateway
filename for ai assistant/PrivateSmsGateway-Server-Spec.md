# Private SMS Gateway: Server-Side Specification (for AI assistants)

**How to use this file:** give it to any AI coding assistant together with one line naming your stack, for example
*"Build the server side described in this spec using Laravel 11 and MySQL."* (or Node/Express + PostgreSQL,
Django, plain PHP, WordPress plugin, etc.). The AI should build everything in section 9 and treat sections 2 to 7
as requirements, not suggestions.

If the stack is not stated, the AI must ask for it in one short question before writing code.

---

## 1. What this is

**Private SMS Gateway** is an open-source Android app. The owner puts a SIM card in an Android phone, installs
the app, and points it at *their own server*. The app **polls the server over HTTPS**, sends the SMS messages the
server hands it using the phone's SIM, and **reports the result back**. The server never connects to the phone;
the phone always connects out, so it works behind NAT and with a changing IP.

The server side you are building is therefore:

1. A **message queue** (database table) holding SMS to send.
2. A **poll endpoint** the phone calls to claim the next message.
3. A **result endpoint** the phone calls to report `sent`, `delivered` or `failed`.
4. A way for the owner's own application to **enqueue** messages.
5. Housekeeping: timeouts, expiry, and an offline alert.

```
Owner's app --enqueue--> [ queue ] <--poll (claims)-- Phone app --SMS--> recipient
                              ^------ result (sent/delivered/failed) ------|
```

## 2. Terms

| Term | Meaning |
|---|---|
| Device | The Android phone running the app |
| Poll URL | Endpoint the app calls to get work (configured in the app) |
| Result URL | Endpoint the app calls to report outcomes. May contain the placeholder `{id}` |
| Device token | Secret the app sends with every request |
| Claim | Atomically moving a message from `pending` to `sending` when handing it to the device |

## 3. Poll endpoint (REQUIRED)

**Request from the app**
* Method: `GET` (default) or `POST`, as configured by the user. Support at least `GET`; supporting both is better.
* Headers always sent: `Accept: application/json`, `User-Agent: PrivateSMSGateway/<version>`, plus the auth header (section 5).
* On `POST` the body is JSON: `{"device": "<device name>", "app": "PrivateSMSGateway"}`. Ignore it if you don't need it.
* The app polls every N seconds (default **15**, minimum 3, user-configurable) and backs off exponentially on errors.
  The endpoint must be **cheap** (one indexed query) and must tolerate this frequency 24/7.

**Responses**

| Situation | Response |
|---|---|
| Nothing to send | `204 No Content` (an empty `200` is also accepted) |
| A message to send | `200` with `Content-Type: application/json` and one of the bodies below |
| Bad or missing token | `401` |
| Server error | `5xx` (the app retries with back-off) |

**Accepted `200` bodies** (the app accepts any of these; **prefer the first**):

```json
{ "id": "123", "to": "+923001234567", "body": "Your code is 4821" }
```
```json
{ "messages": [ { "id": "1", "to": "+92...", "body": "a" }, { "id": "2", "to": "+92...", "body": "b" } ] }
```
(a bare JSON array of message objects is also accepted)

Field notes:
* `id`: string or number; **required** if you want results reported. Must be unique and stable. It is echoed back in the result.
* `to`: recipient phone number. Send **E.164** (`+` and digits, 5 to 15 digits). The app also tolerates spaces, dashes and
  leading `00`, and can expand a leading `0` using a user-set default country code, but do not rely on that.
* `body`: message text. Any length (the app splits long messages into parts automatically). Plain UTF-8; non-GSM
  characters (Urdu, emoji) are allowed but reduce the per-SMS limit to about 70 characters.
* `sim` (optional): `1` or `2` to force a SIM slot for this message. Omit to use the app's setting.
* The field names (`id`, `to`, `body`, `sim`) are configurable in the app. **Use these defaults** unless told otherwise.

**Critical behaviour: claim atomically.** When returning a message you must, in the same transaction, change its status
from `pending` to `sending` and record `claimed_at`. Use row locking (`SELECT ... FOR UPDATE SKIP LOCKED`,
`lockForUpdate()`, an atomic `UPDATE ... WHERE status='pending' ... LIMIT 1` with a returning clause, etc.) so the same
message is never given to two requests. Return **oldest first**.

Returning several messages per poll is allowed (the app sends them one by one with a configurable delay, then polls
again after about 1 second). **Return one at a time by default**, or cap the batch at a small number (e.g. 5).

## 4. Result endpoint (REQUIRED)

**Request from the app**
* Method: `POST`, `Content-Type: application/json`, same auth header as polling.
* URL: the user-configured Result URL with `{id}` replaced by the URL-encoded message id.
  Support the form `POST /.../{id}/result`. If the user's URL has no `{id}`, the id is only in the body.
* Body:

```json
{
  "id": "123",
  "status": "sent",
  "error": null,
  "timestamp": "2026-11-06T12:00:00+05:00",
  "device": "Pixel 6"
}
```

| `status` | Meaning |
|---|---|
| `sent` | The phone's radio accepted the message. All parts sent. |
| `delivered` | Carrier delivery report confirmed delivery (only if the carrier supplies it). Always follows `sent`. |
| `failed` | Not sent, or delivery failed. `error` is a short human-readable string (e.g. `no service`, `invalid phone number`, `SMS permission not granted on the phone`, `hourly send limit reached`, `delivery failed`). |

**Required server behaviour**
* Respond `200` with `{"ok": true}` on success.
* **Be idempotent.** The app queues results on disk and retries (up to 10 attempts), so the same result can arrive more
  than once, and results can arrive **out of order** (e.g. `delivered` before `sent` is unlikely but possible).
* Use a **monotonic state machine** (section 6). Never move a message backwards (e.g. do not overwrite `delivered` with `sent`).
  A `failed` that arrives after `sent` is legitimate (delivery failure) and should be recorded.
* Unknown message id: return `404` (the app treats other 4xx, except 408 and 429, as "drop this result, don't retry").
* Invalid `status`: return `422`.
* Return `5xx` only for genuine server errors; the app will retry.

## 5. Authentication (REQUIRED)

The user picks one method in the app. **Implement bearer tokens as the default**, and make the check easy to adapt:

| App auth setting | What the app sends |
|---|---|
| Bearer token | `Authorization: Bearer <token>` |
| Basic | `Authorization: Basic base64(user:password)` |
| Custom header | `<Header-Name>: <token>` (default name `X-API-Key`) |
| Query parameter | `?<param>=<token>` appended to the URL (default name `token`) |
| None | nothing (only acceptable on a private network) |

Rules:
* Compare tokens with a **constant-time** function (`hash_equals`, `crypto.timingSafeEqual`, `hmac.compare_digest`).
* The device token is a **separate secret** from any token your own application uses to enqueue messages.
* Generate tokens with at least 32 random bytes (64 hex characters). Never hard-code them; read from environment/config.
* Reject with `401` before touching the database.
* Serve over **HTTPS** in production.

## 6. Message state machine

```
pending --(claimed by poll)--> sending --(result: sent)--> sent --(result: delivered)--> delivered
   |                              |                          |
   |                              +--(result: failed)--> failed <--(result: failed, delivery failure)--+
   +--(expired, never picked up)--> failed
sending --(no result before timeout)--> failed   (or back to pending: see below)
```

| Status | Set by |
|---|---|
| `pending` | enqueue |
| `sending` | poll endpoint (claim) |
| `sent`, `delivered`, `failed` | result endpoint, or the housekeeping job |

Allowed transitions on a result: `sending -> sent | failed`, `sent -> delivered | failed`, `delivered` is final
(ignore later results with `200 OK`), `failed` may be overwritten by `sent` only if it was set by timeout/expiry (late result).

**Housekeeping job (REQUIRED, run every minute):**
1. **Stuck in `sending`** longer than **3 minutes** (phone died mid-send) -> mark `failed` with error `timeout: no result from phone`.
   **Do not auto-resend by default.** The phone may have actually sent it and only the report was lost, and for OTP codes a
   duplicate or late SMS is worse than a failure. Make "requeue stuck messages" an opt-in setting for non-sensitive messages.
2. **Stuck in `pending`** longer than **10 minutes** (phone offline) -> mark `failed` with error `expired: phone did not pick it up`.
   Make this TTL configurable per message or per message type (OTP: short; notifications: longer).

**Offline alert (REQUIRED):** record `last_seen_at` on **every authenticated poll**. If the device has not polled for
**10 minutes**, notify the owner (email, Telegram, or log) **once per outage** (re-arm when the device returns). Expose a
`GET /device/status` style check or a field in the admin view showing last seen.

## 7. Enqueue API (REQUIRED)

The owner's own application needs a simple way to create messages. Provide **both**:

1. An **internal function/service** in the chosen stack, e.g. `Sms::queue($to, $body, $options)`.
2. *(Optional but recommended)* an **HTTP endpoint** `POST /api/sms` protected by a **different** token than the device token,
   accepting `{"to": "...", "body": "...", "ttl_seconds": 300}` and returning `{"id": ...}`.

Enqueue must: normalize the number to E.164 (accept a default country code setting), reject empty bodies, enforce a maximum
body length (e.g. 1000 characters), and store `status = pending`.

**Abuse protection (REQUIRED for any endpoint that triggers an SMS from user input, especially OTP/login/signup):**
* Rate limit **per phone number** (e.g. 1 SMS per 60 seconds, 5 per hour) **and per IP**. The owner pays per SMS and a
  hostile client could drain the SIM balance ("SMS pumping").
* Optionally block numbers by country/prefix allow-list.

## 8. Data model (minimum)

```
sms_messages
  id            primary key (also the value sent as "id")
  to            string, E.164
  body          text
  status        enum: pending | sending | sent | delivered | failed   (index with created_at)
  error         text, nullable
  attempts      int default 0           (optional)
  expires_at    timestamp, nullable     (optional per-message TTL)
  claimed_at    timestamp, nullable
  sent_at       timestamp, nullable
  delivered_at  timestamp, nullable
  created_at / updated_at
device_state (or cache)
  last_seen_at  timestamp               (updated on every authenticated poll)
  last_alert_at timestamp, nullable
```

## 9. What the AI must deliver

1. **Database migration / schema** for section 8.
2. **Poll endpoint** (section 3) with atomic claim.
3. **Result endpoint** (section 4) with idempotent, monotonic state handling.
4. **Auth middleware/guard** (section 5) with constant-time comparison and config-driven token.
5. **Enqueue service** and optional HTTP endpoint (section 7) with number normalization and rate limiting.
6. **Scheduled housekeeping** (section 6): timeouts, expiry, offline alert, wired into the stack's scheduler (cron entry included).
7. **Config / `.env.example`** listing: device token, enqueue token, default country code, timeouts, alert email/Telegram settings.
8. **Automated tests** covering: claim atomicity (two parallel polls never get the same message), 204 when empty, 401 on bad token,
   state-machine transitions, idempotent duplicate results, out-of-order results, timeout and expiry jobs.
9. **A short README** with setup steps, the **exact values to type into the app** (Poll URL, Result URL with `{id}`, auth method, token), **and a ready-to-import settings JSON block** (section 13) filled in for the user's domain and token.
10. *(Optional)* a tiny admin page or CLI command listing recent messages with status, error and device last-seen.

## 10. Do and do not

**Do**
* Keep the poll endpoint fast and side-effect-light (it is hit about every 15 seconds, all day).
* Log message ids, statuses and errors. Mask phone numbers in logs.
* Make every threshold (timeouts, TTLs, rate limits) configurable.
* Use the framework's standard validation, ORM/query builder and scheduler; no unusual dependencies.

**Do not**
* Do not log or store message bodies beyond what the queue needs (OTP codes should ideally be stored hashed elsewhere
  and the SMS body deleted or redacted after `sent`).
* Do not call the phone or require it to be reachable from the internet.
* Do not return a message from the poll endpoint without claiming it.
* Do not add required fields or endpoints the app does not know about. The app only calls the poll and result URLs.
* Do not expose the device token in client-side code or URLs shared publicly (prefer the `Authorization` header over a query parameter).

## 11. Acceptance checks (replace the host and token)

```bash
H="https://example.com/api/device/sms"; T="DEVICE_TOKEN"

# 1) Empty queue -> 204
curl -i -H "Authorization: Bearer $T" "$H/next"

# 2) Bad token -> 401
curl -i -H "Authorization: Bearer wrong" "$H/next"

# 3) After enqueueing one message: 200 + JSON with id/to/body, and its status becomes 'sending'
curl -i -H "Authorization: Bearer $T" "$H/next"

# 4) Polling again immediately -> 204 (the message was claimed, not handed out twice)
curl -i -H "Authorization: Bearer $T" "$H/next"

# 5) Report results (repeat each twice to prove idempotency)
curl -i -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
     -d '{"id":"1","status":"sent","error":null,"timestamp":"2026-11-06T12:00:00+05:00","device":"test"}' "$H/1/result"
curl -i -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
     -d '{"id":"1","status":"delivered"}' "$H/1/result"

# 6) A late "sent" after "delivered" must NOT move the message back (still delivered, response 200)
curl -i -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
     -d '{"id":"1","status":"sent"}' "$H/1/result"

# 7) Unknown id -> 404 ; invalid status -> 422
```

## 12. Values the user types into the app

| App setting | Value |
|---|---|
| Poll URL | `https://<domain>/<path>/next` |
| Poll method | GET |
| Result URL | `https://<domain>/<path>/{id}/result` |
| Auth method | Bearer token |
| Token | the device token from the server's config |
| JSON field names | leave at defaults: `id`, `to`, `body`, `sim` |

The AI should print these exact values, filled in for the user's domain, at the end of its README, **and also give the one-paste alternative in section 13**.


## 13. Ready-to-import settings (clipboard import)

The app can load all of its settings from a JSON object on the clipboard, so the user does not have to type anything.
**The AI must output this block at the end of its answer and in the README**, with the user's real domain and token filled in.

**How the user imports it**
1. Copy the JSON (only the JSON, nothing before or after it; no ```` ``` ```` fences, no comments).
2. In the app open **Settings**, scroll to **Backup**, tap **Import settings from clipboard**.
3. A toast says *Settings imported*. If it says *Clipboard doesn't contain valid settings JSON*, the clipboard held something else or the JSON is malformed.
4. Go to **Status** and press **Poll now** to test, then switch the gateway on.

**Import rules (how the app behaves)**
* The whole clipboard text must parse as one JSON **object**.
* Keys that are present overwrite the phone's current value; keys that are absent keep the phone's current value;
  unknown keys are ignored. **Include every key below** so the result is exactly what the AI intended.
* `token` is imported if present. (The app's own *Export* never includes the token, but import accepts it.)
* Numbers must be JSON numbers and booleans must be `true`/`false` (not strings).

**All keys, types and defaults**

| Key | Type | Default | Meaning |
|---|---|---|---|
| `pollUrl` | string | `""` | Full poll URL, e.g. `https://example.com/api/device/sms/next` |
| `pollMethod` | `"GET"` or `"POST"` | `"GET"` | HTTP method for polling |
| `resultUrl` | string | `""` | Full result URL containing `{id}`, or `""` to disable reporting |
| `authType` | `"NONE"`, `"BEARER"`, `"BASIC"`, `"HEADER"`, `"QUERY"` | `"BEARER"` | Auth method (section 5). Must be upper case |
| `authUser` | string | `""` | Username (only for `BASIC`) |
| `token` | string | `""` | Bearer token / API key / Basic password |
| `authName` | string | `"X-API-Key"` | Header name (`HEADER`) or query parameter name (`QUERY`); unused for the others |
| `extraHeaders` | string | `""` | Extra headers, one per line as `Name: value`, lines separated by `\n` |
| `timeoutSec` | int | `20` | HTTP timeout |
| `pollIntervalSec` | int | `15` | Seconds between polls (minimum 3) |
| `maxBackoffSec` | int | `300` | Longest wait after repeated errors |
| `wifiOnly` | bool | `false` | Poll only on Wi-Fi/Ethernet |
| `quietEnabled` | bool | `false` | Do not poll during quiet hours |
| `quietStartHour` | int 0-23 | `23` | Quiet hours start |
| `quietEndHour` | int 0-23 | `6` | Quiet hours end |
| `simSlot` | `0`, `1` or `2` | `0` | 0 = phone default, 1 = SIM 1, 2 = SIM 2 |
| `sendDelaySec` | int | `3` | Pause between messages in a batch |
| `maxPerHour` | int | `0` | Hourly send cap (0 = unlimited) |
| `defaultCountryCode` | string | `""` | Digits only, e.g. `"92"`; turns `0300...` into `+92300...` |
| `reportDelivery` | bool | `true` | Ask the carrier for delivery reports and send `delivered` |
| `idField` | string | `"id"` | Name of the id field in the poll response |
| `toField` | string | `"to"` | Name of the phone-number field |
| `bodyField` | string | `"body"` | Name of the message-text field |
| `simField` | string | `"sim"` | Name of the optional SIM field |
| `startOnBoot` | bool | `true` | Restart the gateway after a reboot |
| `wakeLock` | bool | `true` | Keep the CPU awake while running |
| `deviceName` | string | phone model | Label sent to the server in `device` |

**Template (the AI fills in the domain and token; keep every key)**

```json
{
  "pollUrl": "https://YOUR-DOMAIN/api/device/sms/next",
  "pollMethod": "GET",
  "resultUrl": "https://YOUR-DOMAIN/api/device/sms/{id}/result",
  "authType": "BEARER",
  "authUser": "",
  "token": "YOUR-DEVICE-TOKEN",
  "authName": "X-API-Key",
  "extraHeaders": "",
  "timeoutSec": 20,
  "pollIntervalSec": 15,
  "maxBackoffSec": 300,
  "wifiOnly": false,
  "quietEnabled": false,
  "quietStartHour": 23,
  "quietEndHour": 6,
  "simSlot": 0,
  "sendDelaySec": 3,
  "maxPerHour": 0,
  "defaultCountryCode": "",
  "reportDelivery": true,
  "idField": "id",
  "toField": "to",
  "bodyField": "body",
  "simField": "sim",
  "startOnBoot": true,
  "wakeLock": true,
  "deviceName": "My SMS Phone"
}
```

**Rules for the AI producing this block**
* Output it as a single fenced `json` code block (so the chat's *Copy* button copies only the JSON), with a one-line instruction
  to the user: *"Copy this, then in the app: Settings > Backup > Import settings from clipboard."*
* Replace `YOUR-DOMAIN` and `YOUR-DEVICE-TOKEN` with the real values. Match `pollUrl`/`resultUrl` to the routes it actually built,
  including any `/api` prefix. `resultUrl` must contain the literal text `{id}` unless the server's result route takes the id only from the body.
* If the server was built with a non-default auth method, set `authType`/`authName`/`authUser` accordingly. If it uses
  non-default JSON field names, set `idField`/`toField`/`bodyField` to match.
* Set `defaultCountryCode` if the owner mentioned their country (e.g. Pakistan: `"92"`).
* **Security warning to print next to the block:** it contains the device token. Paste it only on the owner's own phone,
  and clear the clipboard after importing. Other apps may be able to read the clipboard. For extra safety, leave `"token": ""`
  and type the token in the app by hand.

---

*Spec version 1.1, matching Private SMS Gateway app v0.1.x. Reference implementations (Laravel and Node) are in the app
repository under `server-examples/`.*
