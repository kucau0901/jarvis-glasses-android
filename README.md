# Jarvis on Ray-Ban Meta glasses

An Android app for a [Jarvis](https://github.com/kucau0901/jarvis-voice-agent) deployment: talk to
Jarvis through Ray-Ban Meta glasses with nothing to tap on the frame, and use Jarvis's web app
features on the phone.

**In the glasses**

- **"Hey Meta, open Jarvis"** starts a conversation. After each answer the microphone opens again
  for a follow-up; "thanks", "that's all", "dah", "terima kasih" or silence ends it.
- **Jarvis speaks first**: reminders, "time to leave", routines, job results and anything raised
  from Home Assistant are said in the glasses, then your reply is listened for.
- **"What am I looking at?"** (or "apa ni?") takes a photo with the glasses' camera and sends it
  with the question.
- **Live voice** (a switch): GPT-Live through Jarvis for a natural back-and-forth you can talk
  over. Needs Jarvis's `OPENAI_API_KEY`, costs about $0.05 a minute, and hangs up after a quiet
  time you choose (10 s by default) or when you say goodbye.

**On the phone**, in the look of the Even Realities app, light or dark (Settings → Theme):

| Tab | What's in it |
| --- | --- |
| Home | Talk (works without the glasses too: the phone becomes a speakerphone), Jobs, Routines, the Live voice switch, and your Home panels from Jarvis, live. |
| Chat | Typing to Jarvis in the look of Hark Pro: the same history as the web app's Chat, photos, pills showing what Jarvis is doing, rich answers (lists, tables, links), email drafts to send. |
| Inbox | Jarvis's proposals waiting for your OK, and family messages. |
| Settings | The Jarvis address and sign-in, the glasses, voice, theme, and for a family admin **Jarvis settings**: everything in the web app's Settings panel. |

Nothing on the Jarvis side changes: the app uses the same API as Jarvis's web app and devices.

## Set up

In this order. Steps 1–3 get the app talking to Jarvis; step 4 is the glasses.

### 1. Build and install

Needs JDK 17 and the Android SDK (platform 36), and the phone connected with USB debugging. Meta's
toolkit comes from Maven Central; nothing else to sign in to.

```bash
git clone https://github.com/kucau0901/jarvis-glasses-android.git
cd jarvis-glasses-android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew installDebug
```

(On Linux the SDK is usually `~/Android/Sdk`.) The phone must be 64-bit (arm64), Android 12 or
newer.

Open **Jarvis** on the phone and allow Bluetooth, the microphone and notifications. Then
**Settings → Phone → Let Jarvis run in the background**, or Android stops the app in your pocket
and alerts wait.

### 2. Sign in to your Jarvis

In the app, **Settings → Jarvis**:

1. **Address**: your Jarvis, e.g. `https://jarvis.example.com`. A Jarvis at home without a
   domain works too, over `http://` on your home network only (`http://192.168.1.20:8787`,
   `.local`, `.lan`, Tailscale's `100.x` addresses).
2. If your Jarvis sits behind Cloudflare Access, do step 3 first.
3. **Sign in with a code.** A six-letter code appears. In Jarvis on another screen where you are
   signed in: **menu → Family → Sign in another screen**, type the code, **Sign it in**. The app
   then shows **Signed in: <your name>**, and Home, Chat and Inbox are yours.

Instead of signing in as a person, the app can run as a **device** (voice only, no Home panels,
Chat history or Inbox): in Jarvis, **Devices → add a device** with the scopes `ask`, `voice`,
`alerts` and `memory.read` (add `home` for quick house commands through Assist), copy its `jdv1_…`
token (shown once), and paste it under **Device token**.

### 3. Behind Cloudflare Access (only if yours is)

The app can't do Cloudflare's browser login, so give it a service token:

1. Cloudflare One → **Access → Service credentials → Create Service Token**, named e.g.
   `jarvis-phone-app`. Copy the **Client ID** and **Client Secret** (the secret is shown once).
2. In your Jarvis **Access application**, add a policy with action **Service Auth** that includes
   that service token. Your own login policy stays as it is.
3. In the app: **Settings → Jarvis → Behind Cloudflare Access**, paste both, **Save**.

The app sends the token with every request, the alerts connection and live voice included. Anything
without it still meets the normal Cloudflare login.

### 4. The glasses

Pair the glasses with the **Meta AI** app as usual. That is all the app needs to use them as a
headset: Talk, spoken alerts and follow-ups work at once.

For **"Hey Meta, open Jarvis"** and the **camera**, Jarvis has to be a private Beta app in Meta's
Wearables Developer Center, with its IDs built into the app. It takes about half an hour, most of
it waiting for Meta, and has a few traps (Developer Mode, for one, does *not* work for "Hey Meta"
on Android). Follow **[docs/meta-glasses.md](docs/meta-glasses.md)** step by step.

### 5. Use it

- **"Hey Meta, open Jarvis"**, wait for the beep, ask. Follow up straight away; say "thanks" to end.
- **Talk** on Home or on the app's notification does the same from the phone. It reads **Stop**
  while Jarvis is busy.
- **Live voice** (Home or Settings → Voice) switches Talk and "Hey Meta" to GPT-Live. Choose how
  long a quiet session stays open under Settings → Voice.
- **Settings → Voice → Say alerts in the glasses** decides whether Jarvis may speak first.

## Home events and mail

Jarvis already speaks first for its own reminders, routines and jobs. For something at home, a
Home Assistant automation tells Jarvis, which says it in the glasses:

```yaml
# configuration.yaml — a device token holding only `alerts` (notify) or `routines` (trigger)
rest_command:
  jarvis_notify:
    url: https://jarvis.example.com/api/v1/notify
    method: post
    headers:
      Authorization: !secret jarvis_alerts_token
    content_type: application/json
    payload: '{"title": "{{ title }}", "text": "{{ text }}"}'
  jarvis_trigger:
    url: https://jarvis.example.com/api/v1/trigger
    method: post
    headers:
      Authorization: !secret jarvis_routines_token
    content_type: application/json
    payload: '{"event": "{{ event }}"}'
```

```yaml
# automation: the washing machine is done
action:
  - service: rest_command.jarvis_notify
    data:
      title: Laundry
      text: The washing machine has finished.
```

`/api/v1/trigger` runs Jarvis's `event` routines (which can ask something and say the answer);
`/api/v1/notify` says a fixed text. The tokens are `Bearer jdv1_…` in `secrets.yaml`.

Mail: a Jarvis routine (e.g. hourly, "anything important in my mail?") speaks when there is
something.

## How it works

| Part | Where |
| --- | --- |
| Microphone and speakers | The glasses as a Bluetooth headset (HFP/SCO); without them, the phone as a speakerphone: `audio/GlassesAudio.kt`. |
| End of a question | Loudness only, no touch: `audio/SpeechGate.kt` (1.2 s of quiet after speech). |
| A spoken question | `POST /api/v1/voice` twice: hear it, then ask the words with the conversation so far and a photo when one was asked for; the answer streams back sentence by sentence (`jarvis/JarvisClient.kt`). |
| The conversation | `conversation/Conversation.kt`: listen → hear → stop? photo? → ask → play → listen again. |
| Alerts | WebSocket `/api/v1/events` (`jarvis/AlertSocket.kt`). Taken as seen only when the glasses are connected and the app is free; otherwise Jarvis goes on to the phone's notifications. |
| Registration, camera, "Hey Meta" | Meta's Wearables Device Access Toolkit 1.0.1: `glasses/GlassesLink.kt`. |
| Live voice | WebRTC (`live/LiveVoice.kt`) to Jarvis's `/api/session`; tool calls relayed to `/api/v1/stream` as the web app does (`live/LiveRelay.kt`). |
| Signing in | Jarvis's pairing code (`jarvis/Pairing.kt`); the person's session reaches `/api/hub/…` (`hub/Hub.kt`) and, for an admin, the settings API (`hub/Admin.kt`). |
| Chat | `ui/ChatScreen.kt`, its pills (`chat/Steps.kt`) and answers (`chat/Markdown.kt`, `ui/Answer.kt`), in the Hark look (`ui/Hark.kt`). |
| Connecting a ChatGPT plan | The sign-in ends on `http://127.0.0.1:1455`, which the app answers itself (`jarvis/Loopback.kt`): nothing to paste. |
| Running in a pocket | `JarvisService.kt`, a foreground service (microphone, connected device). |

## Test

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the event stream, alerts, pairing, the Jarvis API calls, end-of-speech detection,
the phrases, the conversation loop with fakes, the live relay, the Home layout, Chat's pills and
answers, and the ChatGPT sign-in catcher. Meta's Mock Device Kit (debug builds) stands in for
glasses. `.mcp.json` connects Meta's toolkit docs server.

Not covered by tests, to try with the glasses: the headset routing, "Hey Meta", the camera, live
voice's WebRTC, and an alert arriving with the phone locked.

## Limits

- Meta's toolkit is a developer preview: "Hey Meta" launch can't be published to Meta's store yet.
  This app is for your own glasses, through your own Beta channel.
- Only a headset named like Meta glasses ("Ray-Ban", "Meta", "Oakley") counts, so a car or
  earbuds connected at the same time are not used.
- Alerts are spoken one question at a time even with Live voice on, and not while a live session
  is open (they go to the phone instead).
- arm64 phones only (the WebRTC libraries are large).
