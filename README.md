# Private SMS Gateway

An open-source Android app that turns a phone and its SIM card into an SMS gateway for **your own server**.
The app polls a URL you configure, sends the SMS it receives, and reports the result back. Nothing passes
through anyone else's servers, and it works with any backend that can answer two HTTP requests
(Laravel, WordPress, Node, plain PHP, ...).

> **Status: first version, not yet compiled or tested on a device.** Expect to fix a few small build
> errors on first run. Please open issues or pull requests.

## How it works

```
Your server  <--- poll (HTTPS) ---  Phone (this app)  --- SMS --->  Recipient
   queue                            polls every N seconds
   pending -> sending -> sent -> delivered / failed   <--- result (HTTPS) ---
```

The phone always connects out to your server, so it works behind NAT, with a changing IP, on mobile data
or Wi-Fi. No port forwarding or VPN is needed.

## Quick start

1. Install the APK (see **Building**) and open the app.
2. **Settings**: enter your Poll URL, choose the auth method, paste your token. Optionally set the Result URL.
3. **Status**: grant the permissions in the checklist (SMS, phone state, notifications, battery).
4. Switch the gateway **on**, press **Send test SMS** to verify the SIM, then **Poll now** to verify the server.

## Server API contract

### 1. Poll: the app asks for work

`GET {poll_url}` (or `POST`, which sends `{"device": "...", "app": "PrivateSMSGateway"}`), with your auth.

* `204 No Content` (or an empty `200`): nothing to send.
* `200` with JSON, in any of these shapes:

```json
{ "id": "123", "to": "+923001234567", "body": "Your code is 4821" }
```
```json
[ { "id": "1", "to": "+92...", "body": "a" }, { "id": "2", "to": "+92...", "body": "b" } ]
```
```json
{ "messages": [ { "id": "1", "to": "+92...", "body": "a" } ] }
```

Optional per-message field `"sim": 1` or `2` overrides the SIM chosen in settings.
Field names are configurable in **Settings > JSON field names**, so an existing API can work unchanged.

Your server should **mark the message as "sending" when it hands it out** (so it is never given twice)
and time out messages that never get a result.

### 2. Result: the app reports what happened

`POST {result_url}` with `{id}` replaced in the URL (URL-encoded):

```json
{ "id": "123", "status": "sent", "error": null, "timestamp": "2026-11-06T12:00:00+05:00", "device": "Pixel 6" }
```

`status` is `sent` (accepted by the network), `delivered` (carrier delivery report, only if your carrier
supplies it and "Report delivery status" is on) or `failed` (with `error` set; a `failed` can also follow a
`sent` if delivery fails). Results are queued on the phone and retried, so brief outages don't lose them.
Respond with any `2xx`. A `4xx` (other than 408/429) tells the app to drop the result.

Ready-to-use examples are in `server-examples/` (Laravel routes + migration, and a 40-line Node server).

## Settings reference

| Section | Setting | Notes |
|---|---|---|
| Server | Poll URL / method, Result URL | `{id}` placeholder supported in Result URL |
| Auth | None, Bearer, Basic, Custom header, Query param | Token stored encrypted (Android Keystore) |
| Auth | Extra headers | One `Name: value` per line |
| Polling | Interval, max back-off, timeout | Exponential back-off on errors |
| Polling | Wi-Fi only, quiet hours | |
| Sending | SIM (default / 1 / 2), delay between messages | |
| Sending | Max per hour, default country code | Hourly cap protects SIM balance; `0123...` becomes `+<code>123...` |
| Mapping | JSON field names | id, to, body, sim |
| Reliability | Start on boot, wake lock, device name | |
| Backup | Export / import settings as JSON | Export never includes the token |

## Building

* **Android Studio** (Koala or newer, JDK 17): open this folder, wait for Gradle sync, press Run.
* **Command line**: `./gradlew assembleDebug`, then find the APK in `app/build/outputs/apk/debug/`.
* **GitHub Actions**: push to GitHub and the included workflow builds a debug APK as a downloadable artifact.

For a release build, create your own keystore and signing config (don't commit it).

## Permissions

| Permission | Why |
|---|---|
| `SEND_SMS` | Sending messages |
| `READ_PHONE_STATE` | Listing SIM slots so you can pick SIM 1 or 2 |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Polling your server, Wi-Fi-only option |
| `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS` | Keeping the gateway alive with a visible notification |
| `RECEIVE_BOOT_COMPLETED` | Starting again after a reboot |
| `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reliability |

## Security notes

* **Use HTTPS.** Plain `http://` is allowed (for trusted local networks) but the app warns about it.
* Use a long random token (`php -r "echo bin2hex(random_bytes(32));"`).
* The app never logs message text and masks phone numbers in its log. `allowBackup` is off.
* **Rate-limit whatever creates messages on your server** (OTP endpoints especially) so abuse can't drain your SIM balance.
* Anyone who can queue messages on your server can send SMS from your SIM. Protect that endpoint.

## Limitations

* **Not for Google Play.** Play only allows SMS permissions for default-SMS apps. Distribute via GitHub Releases or F-Droid.
* Some phone brands (Xiaomi, Oppo, Vivo, Huawei, Samsung) kill background apps. Disable battery optimization,
  enable autostart, and see https://dontkillmyapp.com. Keep the phone plugged in.
* Carriers may rate-limit or flag SIMs that send many messages. This is designed for low volume.
* "sent" means the phone's radio accepted the message. "delivered" depends on carrier delivery reports.

## Roadmap

* Forward incoming SMS to your server (webhook)
* Push instead of polling (UnifiedPush / optional FCM), long-polling mode
* QR code settings import
* Per-message retry and scheduling

## License

Apache-2.0, see `LICENSE`.
