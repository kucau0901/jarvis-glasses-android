# Jarvis on Ray-Ban Meta glasses

An Android app that holds a spoken conversation with a
[Jarvis](https://github.com/kucau0901/jarvis-voice-agent) deployment through Ray-Ban Meta
glasses, without touching them:

- **"Hey Meta, open Jarvis"** starts a conversation (Meta's Voice Invocations).
- After each answer the microphone opens again for a follow-up; silence, "thanks", "that's all",
  "dah" or "terima kasih" ends it.
- **Jarvis speaks first**: reminders, "time to leave", routines, job results and anything raised
  from Home Assistant are said in the glasses, and the reply is listened for, with the alert as
  context ("yes, remind me in an hour").
- **"What am I looking at?"** (or "apa ni?") takes a photo with the glasses camera and sends it
  with the question.
- **Talk** on the app's notification starts a conversation from the phone.
- **Live voice** (a switch): GPT-Live through Jarvis instead of question by question, for a
  natural back-and-forth. It needs Jarvis's `OPENAI_API_KEY`, costs about $0.05 a minute, and
  closes itself after 30 s of quiet; without a key it falls back to question by question.

Nothing on the Jarvis side changes: the app uses the device API (`docs/api.md` there).

## How it works

| Part | Where |
| --- | --- |
| Microphone and speakers | The glasses as a Bluetooth headset (HFP/SCO): `audio/GlassesAudio.kt`. The phone's own microphone is never opened. |
| End of a question | Loudness only, no touch: `audio/SpeechGate.kt` (1.2 s of quiet after speech). |
| A question | `POST /api/v1/voice` twice: hear it (`transcribe`), then ask the words with the conversation so far as `context` and a photo when one was asked for, the answer streamed back sentence by sentence (`jarvis/JarvisClient.kt`). |
| The conversation | `conversation/Conversation.kt`: listen → hear → stop? photo? → ask → play → listen again. Forgotten after 10 minutes. |
| Alerts | WebSocket `/api/v1/events` (`jarvis/AlertSocket.kt`). Acknowledged as seen only when the glasses are connected and the app is free; otherwise Jarvis goes on to the phone's notifications as usual. |
| Registration, camera, "Hey Meta" | Meta's Wearables Device Access Toolkit 1.0.1: `glasses/GlassesLink.kt`. |
| Live voice | WebRTC (`live/LiveVoice.kt`): the offer goes to Jarvis's `/api/session`, which opens GPT-Live with its own key. Each tool call (`session.delegation.created`) is relayed to `/api/v1/stream` with the conversation so far, and the answer goes back as `session.commentary.append` (`live/LiveRelay.kt`), as Jarvis's web app does. |
| Running in a pocket | `JarvisService.kt`, a foreground service (microphone, connected device). |

## Set up

1. **Jarvis**: in Devices, add a device (e.g. "Glasses") with the scopes `ask`, `voice`, `alerts`
   and `memory.read` (add `home` for fast house commands through Assist). Copy its `jdv1_…`
   token; it is shown once.
2. **Meta AI app**: turn on Developer Mode for the glasses (Settings → your glasses → Developer
   Mode). It can reset after a firmware or app update.
3. **Wearables Developer Center** (wearables.developer.meta.com): a project for package
   `app.jarvis.glasses` with URL scheme `jarvisglasses`; ask for **Voice Invocations** with the
   spoken name "Jarvis", and Camera. Voice Invocations is experimental and needs approval; until
   it is approved, use Talk on the notification.
4. **Build and install** (JDK 17, Android SDK 36; the phone connected with USB debugging):

   ```bash
   ./gradlew installDebug
   ```

5. **In the app**: allow Bluetooth, microphone and notifications; enter the Jarvis address
   (`https://jarvis.example.com`) and the token, **Save**; **Connect Meta AI**; **Allow camera**;
   **Let Jarvis run in the background**.

`local.properties` (not committed) may hold `mwdat_application_id` and `mwdat_client_token` from
the Developer Center; without them the app runs in Developer Mode (`0`).

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
`/api/v1/notify` says a fixed text. `jarvis_alerts_token` is `Bearer jdv1_…` in `secrets.yaml`.

Mail: a Jarvis routine (e.g. hourly, "anything important in my mail?") speaks when there is
something. Mail the moment it arrives would need a change in Jarvis.

## Test

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the event stream, alerts, the WAV header, end-of-speech detection, the phrases,
the whole conversation loop with fakes, and the live relay. The Mock Device Kit (`mwdat-mockdevice`, debug builds)
stands in for glasses for registration and the camera. `.mcp.json` connects Meta's DAT docs server
for looking up the toolkit's API.

Not covered by tests, to try on the glasses: the headset routing, "Hey Meta", the camera, a live
session's WebRTC, and an alert arriving with the phone locked (`curl` a `POST /api/v1/notify`).

## Limits

- The toolkit is a developer preview; Voice Invocations and standalone capture are experimental
  and can't be published to production yet. This app is for your own glasses.
- Only a headset named like Meta glasses ("Ray-Ban", "Meta", "Oakley") counts, so a car or
  earbuds connected at the same time are not used.
- Alerts are said question-by-question even with Live voice on, and not at all while a live
  session is open (they go to the phone instead).
- arm64 phones only (the WebRTC libraries are large).
