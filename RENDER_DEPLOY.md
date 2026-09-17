# SOUYAKU Render Free deployment

This package is ready to deploy the token relay as a Render Web Service.

## 1. Put this project in a Git repository

Keep `render.yaml` at the repository root. The relay code stays in `server/`.

## 2. Create the Render service

In Render:

1. New -> Blueprint
2. Connect the Git repository
3. Select the repository containing this `render.yaml`
4. Deploy the Blueprint

The Blueprint creates one free Node.js Web Service named `souyaku-token-relay`, uses `server/` as its root directory, runs `npm ci --omit=dev`, starts with `npm start`, and checks `/health`.

Render assigns a URL similar to:

`https://souyaku-token-relay-xxxx.onrender.com`

The Android WebSocket URL is the same host with the `wss://` scheme:

`wss://souyaku-token-relay-xxxx.onrender.com`

## 3. Verify the server

Open:

`https://YOUR-SERVICE.onrender.com/health`

Expected JSON starts with:

`{"ok":true,...}`

## 4. Set the Android default WSS URL

After Render gives you the real hostname, add this to `android/gradle.properties`:

`SOUYAKU_TOKEN_CALL_SERVER_URL=wss://YOUR-SERVICE.onrender.com`

Then build the Android app. Users can still override the server URL from the token-call screen.

## 5. Two-device test

1. Install the same build on two Android devices.
2. Set each device's own spoken language.
3. Enter/share the same token.
4. Connect both devices.
5. Confirm `接続台数: 2 / 2`.
6. Speak on device A and verify device B translates and speaks automatically.
7. Repeat B -> A.

## Free-plan behavior

Render Free Web Services spin down after 15 minutes without inbound HTTP requests or WebSocket messages. A new HTTP request or WebSocket connection wakes the service, and wake-up can take around one minute. This makes the free plan suitable for development and limited testing rather than latency-sensitive production calling.
