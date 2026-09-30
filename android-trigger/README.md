# Codex Quota Trigger for Android

Minimal Android app that directly uses the ChatGPT/Codex subscription backend.

## Current MVP

- ChatGPT OAuth 2.0 Authorization Code + PKCE.
- Loopback callback on `127.0.0.1:<random-port>/auth/callback`, matching the Codex CLI style.
- Access/refresh/id tokens encrypted with an Android Keystore AES-GCM key.
- Refresh-token rotation support.
- `GET https://chatgpt.com/backend-api/wham/usage`.
- Minimal `POST https://chatgpt.com/backend-api/codex/responses` trigger.
- Exact Alarm scheduling with Doze-aware `setExactAndAllowWhileIdle`.
- Boot/package-update rescheduling.
- Optional ntfy POST notification.
- Manual Check and Trigger Test buttons.

## Scheduler semantics

1. If `rate_limit.primary_window` exists, schedule the next check for its reset time + 60 seconds.
2. If the primary window is absent and the weekly/secondary window is not blocked, send the minimal Codex request to start a new 5-hour window.
3. If the weekly/secondary limit is blocked, do not trigger; schedule for its reset.
4. Re-read quota after a trigger and schedule the next check.

## Important

The `chatgpt.com/backend-api/codex/*` endpoints are used by Codex clients but are not a documented stable third-party API contract. They may change.

This project intentionally does not import desktop `auth.json`; sign in directly on Android.

## Build

GitHub Actions workflow: `.github/workflows/android-apk.yml`

Local:

```sh
gradle assembleDebug
```
