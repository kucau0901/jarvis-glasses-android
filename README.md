# Jarvis on Ray-Ban Meta glasses

An Android app for a [Jarvis](https://github.com/kucau0901/jarvis-voice-agent) deployment: talk to
Jarvis through Ray-Ban Meta glasses with nothing to tap on the frame, and use Jarvis's web app
features on the phone.

**In the glasses**

- **"Hey Meta, open Jarvis"** starts a conversation. After each answer the microphone opens again
  for a follow-up; "thanks", "that's all", "dah", "terima kasih" or silence ends it, and a closing
  tone says so. Jarvis answers a follow-up only when it was said to it, not the TV or people
  talking near you ([What it hears](#what-it-hears)).
- **Jarvis speaks first**: reminders, "time to leave", routines, job results and anything raised
  from Home Assistant are said in the glasses, then your reply is listened for. Without the
  glasses, they arrive as this app's notifications: **Reply** right there, and Jarvis's answer
  joins the same notification.
- **"What am I looking at?"** (or "apa ni?") as your opening question takes a photo with the
  glasses' camera and sends it with the question.
- **Live voice** (a switch): GPT-Live through Jarvis for a natural back-and-forth you can talk
  over. Needs Jarvis's `OPENAI_API_KEY`, costs about $0.05 a minute, and hangs up after a quiet
  time you choose (10 s by default), 20 s after Jarvis last spoke, or when you say goodbye.

**On the phone**, in the look of the Even Realities app, light or dark (Settings → Theme):

| Tab | What's in it |
| --- | --- |
| Home | Talk (works without the glasses too: the phone becomes a speakerphone), Jobs, Routines, the Live voice switch, and your Home panels from Jarvis, live. |
| Chat | Typing to Jarvis in the look of Hark Pro: the same history as the web app's Chat, photos, pills showing what Jarvis is doing, rich answers (lists, tables, links), email drafts to send. |
| Inbox | Jarvis's proposals waiting for your OK, and family messages. |
| Settings | The Jarvis address and sign-in, the glasses, voice, theme, and for a family admin **Jarvis settings**: everything in the web app's Settings panel. |
| Settings → Location | Off until you turn it on. Save the places that matter ("Corner Grocer", "Office") while standing in them, and the phone tells Jarvis when you arrive at or leave one, so a routine can act on it: "when I get to the grocer, tell me my shopping list". |

Nothing on the Jarvis side changes: the app uses the same API as Jarvis's web app and devices.

## Set up

In this order. Steps 1–3 get the app talking to Jarvis; step 4 is the glasses; step 5, location, is optional.

### 1. Build and install

Needs JDK 17 and the Android SDK (platform 36), and the phone connected with USB debugging. Meta's
toolkit comes from Maven Central; nothing else to sign in to. The first build also installs the
Android NDK and CMake into the SDK (about 1 GB), for the G2 microphone's decoder.

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

### 5. Location (only if you want it)

For routines that act on where you are. Signed in as a person (step 2), in **Settings → Location**:

1. Turn on **Tell Jarvis when I arrive at or leave my places**. Android asks for location: choose
   **Precise** and **While using the app**.
2. Tap **Allow all the time**. Android asks for this separately, on its own settings page: choose
   **Allow all the time** there and come back. Without it nothing is watched, because a place is
   crossed with the phone in your pocket and Jarvis closed. (If Android no longer asks, the button
   opens the app's page in Android's settings: Permissions → Location.)
3. Standing in a place, type its name under **Save where I am now as…** and tap **Save**. Up to
   twenty; the same name again moves that place; **Remove** takes one away.
4. Tell Jarvis what to do there, in Chat or aloud: "when I get to the Corner Grocer, tell me my
   shopping list", "when I leave the Office, tell me the best way home".

**Offer something useful when I arrive** is a second switch, also off: every arrival is then put
to Jarvis, which costs an answer each time, and it alerts you only when something is worth it.
Turning it on makes a routine named "Something useful where I arrive", listed with your others;
turning it off removes it. Ask Jarvis to change what it offers. A routine of your own for arriving
anywhere is used instead, and left alone.

**What leaves the phone.** The phone does the watching, with Android's own geofences; Jarvis is
only told. When you save a place, its name and position go to your Jarvis, because you asked. When
you cross one, Jarvis is sent that you arrived at or left that place, by its name, and nothing
else. Where you are the rest of the time is never sent, the position Android measured at a crossing
is not read, and nobody keeps a trail. A crossing that couldn't be sent is tried again for ten
minutes and then dropped. All of it is off until you turn it on, on each phone, and turning it off
or signing out takes the fences away. It needs Google Play services.

### 6. Use it

- **"Hey Meta, open Jarvis"**, wait for the beep, ask. Follow up within 5 s; say "thanks" to end.
  The closing tone (three falling notes) means Jarvis has stopped listening: say "Hey Meta, open
  Jarvis" again to go on.
- **Talk** on Home or on the app's notification does the same from the phone. It reads **Stop**
  while Jarvis is busy.
- **Live voice** (Home or Settings → Voice) switches Talk and "Hey Meta" to GPT-Live. Choose how
  long a quiet session stays open under Settings → Voice.
- **The browser card.** When Jarvis works in its own browser and the page needs a person (a
  sign-in, an "are you human" check, a choice), it hands the page over: a **Browser** card appears
  in Chat with the page's screen. Press and drag on the picture as you would the page; type in the
  box and tap **Send** (hidden as typed until **Show**), with **Enter**, **Backspace**, **Back**
  and **Up**/**Down** beside it; **Full screen** turns the phone on its side. What you type goes to
  the browser only, never into the conversation. It needs a browser set up on the Jarvis side
  (`BROWSER_URL`) and the `browser` scope, which an admin's sign-in with a code has; without them
  the card says what Jarvis said. The TV app shows the same screen to watch, pressed from a phone;
  the watch and the glasses get no hand-over.
- **Alerts start a conversation.** Every alert is in Chat as it arrives. When Jarvis says to talk
  about it, the glasses say it and listen for your reply: in a live session that opens by saying it
  when Live voice is on, else turn by turn; into the session already open, if one is. Whether Jarvis
  talks about your alerts is yours to choose on every screen, under **Settings → Your alerts**
  (the same as Jarvis's Family → You → Alerts): **Talk to me about alerts**, and for family
  messages, **Say family-room messages too** and where answering one aloud goes (to whoever wrote
  it, or the family room).
- **Settings → Voice → This phone may say alerts** decides whether this phone does; when it
  doesn't, or Jarvis says not to, alerts come as notifications. Android's notification settings for
  the app (channel "Jarvis's alerts") decide how they look and sound.
- **Even Realities G2.** With a G2 paired to the phone in Even's app, and Ray-Ban glasses not in
  use, an alert shows on the G2's display, in **Jarvis's chat**: the same conversation as Chat and
  the web app, newest at the bottom, under a top line saying what Jarvis is doing (*Listening...*,
  *Thinking...*, *Checking the calendar...*) and above one saying what a touch does now. **Tap**
  (the touchpad or the R1 ring) to reply: what you said shows at once, then the answer, written
  for the display. **Swipe up** for earlier messages, **down** for newer ones; at the newest, swipe
  down twice to close (once only asks). A swipe down while it listens stops listening. Tap again
  to follow up; untouched, it goes after a minute. Another alert arriving meanwhile joins the chat
  rather than coming as a notification. Home shows the G2, and its battery from the last time an
  alert was up.
  **"Hey Even, open Jarvis"** shows Jarvis's own screen on the G2: three tiles under the glasses'
  battery and the time, the chosen one framed bright. Swipe to choose, tap to open **Chat** (tap
  again to talk), **Alerts** (counting any that arrive meanwhile; tap to reply) or **Close**.
  Leaving the chat goes back to it. It is only frames and the font's symbols, drawn by the glasses
  themselves, so a swipe shows at once.
  The microphone never opens by itself: the G2 can't say whether it is being worn. The Even app
  stays connected all along, and has the glasses back as soon as the chat closes, so "Hey Even"
  and its own notifications go on working. An arm that drops off Bluetooth for a moment is
  connected again and the chat put back; only after 8 s is it given up on. **Settings → Voice →
  Show alerts on Even G2 glasses** switches it off.
  **Showing it to others:** **Settings → Voice → Record a G2 demo** films with the phone's camera
  and draws what the G2 shows over the middle of the video as it changes, so one video has you
  and your glasses' display in step, nothing to edit. It is saved to Movies/Jarvis, to share from
  there. The G2 screen is drawn from what the app sends the glasses, in the phone's font, so it is
  close to the glasses' look rather than a photo of it.

## What it hears

The glasses' microphone hears everyone near you, not only you: a TV, people talking near you, a
conversation you are having with someone else. With Jarvis 2.8.0 or later, every follow-up says
so (`openMic`), and Jarvis answers only what was said to it. Your opening question after "Hey
Meta, open Jarvis", and a reply to an alert, go without it: Jarvis takes them as said to it.

- **Not said to Jarvis: ignored.** When nothing heard in a follow-up was for Jarvis, nothing is
  answered, nothing is done and nothing is kept: not in Chat, not in your conversation on Jarvis.
  The conversation ends there.
- **The closing tone**, three falling notes unlike the opening beep, means the microphone is shut:
  say "Hey Meta, open Jarvis" again to talk. It plays whenever listening ends: silence, "thanks",
  a follow-up that ran too long, something not said to Jarvis, follow-ups switched off, or a live
  session hanging up by itself. Not when you stop it, or take the glasses off.
- **Follow-ups are short.** After an answer the microphone waits 5 s for you to start. A follow-up
  still going at 15 s is taken for someone else talking: nothing is sent, not even to be turned
  into words, and the conversation ends. Your opening question, or a reply to an alert, may run to
  45 s. "Okay", or the launch phrase heard again, is listened past once per answer, not without end.
- **Photos only with the opening question**, the one right after "Hey Meta, open Jarvis": never
  with a follow-up or a reply to an alert, so nobody else's "what's this?" takes a picture.
- **Kept**: each question Jarvis answered, with its answer, in Chat and in your conversation on
  Jarvis, as before.
- **Live voice** hears everything around you while it is open. Through the glasses, Jarvis 2.8.0
  or later tells GPT-Live so, and that it should stay silent unless spoken to. What it hears is
  cut into stretches at each pause of 1.5 s or more. The app keeps only the stretches Jarvis
  replied to or acted on, and sends only those when Jarvis's tools are asked for something; words
  said in the same stretch as your question go with it. When Jarvis finds a request was set off by
  something not said to it, none of it is kept and nothing is said about it. A session ends 20 s
  after Jarvis last spoke or answered, however much is said around you, besides the quiet time
  and "goodbye", with the closing tone. A request Jarvis is still working on when it hangs up is
  finished all the same, and its answer is in Chat.
- **On a G2** the microphone opens only when you tap, as before, so a follow-up there may run to
  45 s too; something not said to Jarvis gets a short line on the display, and is not kept.

What still leaves the phone: everything recorded in a follow-up goes to Jarvis to be turned into
words and judged, then is dropped if it wasn't for Jarvis; a live session streams everything it
hears to GPT-Live. **Only switching off follow-ups and Live** (Settings → Voice → *Listen for a
follow-up after each answer*, and *Live voice*) **keeps nearby conversations from being recorded
at all.** The microphone then opens only for your opening question after "Hey Meta, open
Jarvis", and for your reply when Jarvis says an alert, up to 45 s each (*This phone may say alerts
in the glasses and listen for a reply*, also under Settings → Voice, switches the replies off).

An older Jarvis doesn't know `openMic`: what is heard is answered as before, and a live session is
opened as on a phone. The rest above is the app's own, and holds with any Jarvis.

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
| End of a question | Loudness only, no touch: `audio/SpeechGate.kt` (1.2 s of quiet after speech; speech still going at 15 s for a follow-up, 45 s otherwise, is too long and never sent). |
| A spoken question | `POST /api/v1/voice` twice: hear it, then ask the words with the conversation so far and a photo when one was asked for; the answer streams back sentence by sentence (`jarvis/JarvisClient.kt`). |
| The conversation | `conversation/Conversation.kt`: listen → hear → stop? photo? → ask (`openMic` for a follow-up) → not for Jarvis? → play → listen again; the closing tone when listening ends. |
| Alerts | WebSocket `/api/v1/events` (`jarvis/AlertSocket.kt`): in Chat at once; when the alert says `talk` and the glasses are connected, said in them (into an open live session, a live session opened with it as `opening`, or turn by turn), else a notification with Reply (`alerts/AlertNotifier.kt`). Either way acknowledged as seen, so Jarvis sends it nowhere else; with this app's notifications switched off in Android, Jarvis goes on to its other ways. |
| Even Realities G2 | Bluetooth, the protocol MentraOS's G2 driver recovered (`g2/G2Wire.kt`), held only while an alert is up (`g2/G2Link.kt`); its LC3 microphone decoded by Google's liblc3 v1.1.3 (`src/main/cpp`, `audio/Lc3Decoder.kt`); the chat on the display (`g2/G2Chat.kt`), the alert and the conversation about it (`g2/G2Alerts.kt`, `g2/G2Ears.kt`), and the menu "open Jarvis" shows (`g2/G2Menu.kt`, told by Jarvis on the alert socket), asking `/api/v1/voice` for an answer that fits the display (`chars`). |
| Registration, camera, "Hey Meta" | Meta's Wearables Device Access Toolkit 1.0.1: `glasses/GlassesLink.kt`. |
| Live voice | WebRTC (`live/LiveVoice.kt`) to Jarvis's `/api/session` (`client: "glasses"` through the glasses, from Jarvis 2.8.0 by `/api/health`); tool calls relayed to `/api/v1/stream` as the web app does, with only the turns Jarvis answered (`live/LiveRelay.kt`). |
| Signing in | Jarvis's pairing code (`jarvis/Pairing.kt`); the person's session reaches `/api/hub/…` (`hub/Hub.kt`) and, for an admin, the settings API (`hub/Admin.kt`). |
| Chat | `ui/ChatScreen.kt`, its pills (`chat/Steps.kt`) and answers (`chat/Markdown.kt`, `ui/Answer.kt`), in the Hark look (`ui/Hark.kt`). |
| The browser card | Jarvis's `display` of kind `browser` (`chat/Shown.kt`): the screen from `GET /api/browser/screen` about twice a second while the card is on screen, presses and typing to `POST /api/browser/input` one request after another (`chat/Browser.kt`, `ui/BrowserCard.kt`, the calls in `hub/Hub.kt`). On the TV, to watch only (`tv/…/BrowserView.kt`). |
| Connecting a ChatGPT plan | The sign-in ends on `http://127.0.0.1:1455`, which the app answers itself (`jarvis/Loopback.kt`): nothing to paste. |
| Places | The list is Jarvis's (`GET`/`POST`/`DELETE /api/v1/places`, in `hub/Hub.kt`), kept on the phone too (`location/Places.kt`) and registered as Android geofences, one per place (`location/Fences.kt`); only what changed is added or removed. A crossing (`location/GeofenceReceiver.kt`) is `POST /api/v1/trigger` with `arrived:<slug>` or `left:<slug>`, and the bare `arrived`/`left` too when offers are on; one that fails waits and is dropped at ten minutes (`location/Outbox.kt`). After a restart or an update the fences are put back from the kept list (`location/BootReceiver.kt`). Jarvis's answer comes as an alert, like any other. |
| Running in a pocket | `JarvisService.kt`, a foreground service (microphone, connected device). |

## Test

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the event stream, alerts, pairing, the Jarvis API calls, end-of-speech detection,
the phrases, the conversation loop with fakes, the live relay, the Home layout, Chat's pills and
answers, the browser card's presses (where on the page a touch lands, and their order), the places calls and the trigger, which geofences to add and remove when the list changes, what a crossing sends and the retry-or-drop rule, the ChatGPT sign-in catcher, and the G2's framing, sign-in, pages, chat and touches. Meta's Mock Device Kit (debug builds) stands in for
glasses. `.mcp.json` connects Meta's toolkit docs server.

Not covered by tests, to try with the glasses: the headset routing, "Hey Meta", the camera, live
voice's WebRTC, the closing tone, an alert arriving with the phone locked, the browser card on a
phone and a TV against a real Jarvis browser (touch, the keyboard, full screen), the G2's link,
microphone and LC3 decoder, and all of Location that needs a phone: the two permission steps
(`MainActivity.kt`), the geofences themselves and how soon Android tells of a crossing
(`location/Fences.kt`, `GeofenceReceiver.kt`), putting them back after a restart or an update
(`BootReceiver.kt`), reading the position for a new place (`Here.kt`), the Settings section
(`ui/LocationChoices.kt`), and what it costs the battery. A debug build shows a test alert on a G2 from the computer:
`adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --es text "'The gate is open.'"`;
`--ei hold 5` instead holds the G2 for five minutes, to see whether the link stays up;
`--ez menu true` opens the menu as "open Jarvis" would; `--ei listen 5` logs, for five minutes,
what the G2 sends while the Even app has it, without signing in.

## Limits

- Meta's toolkit is a developer preview: "Hey Meta" launch can't be published to Meta's store yet.
  This app is for your own glasses, through your own Beta channel.
- Only a headset named like Meta glasses ("Ray-Ban", "Meta", "Oakley") counts, so a car or
  earbuds connected at the same time are not used.
- An alert that comes while a turn-by-turn conversation is going goes to the phone as a
  notification instead.
- Folding the glasses, or taking them off when they had said they were worn, ends a conversation
  in them at once, Talk's or Live's, and nothing more is heard; an alert that comes while they are
  folded is a notification. Meta's toolkit can say "off the face" of glasses being worn, so that
  alone ends nothing. Talk started with them already folded uses the phone, as before.
- The G2's protocol is not published by Even; it was recovered from Even's app (by MentraOS), and
  tried on G2 firmware 2.4.0.24. A firmware update may change it. A double tap is left to the
  glasses, which use it for their menu. Asking Jarvis something yourself on a G2 is still "Hey
  Even", through Even AI ([Jarvis's G2 guide](https://github.com/kucau0901/jarvis-voice-agent/blob/main/docs/even-g2.md)),
  and so is opening the menu: with no Jarvis page up, only a single tap on the R1 ring reaches
  the phone, and the glasses use that for Even's dashboard.
- Opened from inside a Meta AI call, Jarvis gets no sound from the glasses (the call holds them) and
  uses the phone's microphone and speaker. Say "Hey Meta, open Jarvis" outside a call.
- Location: Android decides when a geofence counts as crossed, usually within a couple of minutes
  and later with the phone still or Wi-Fi off, so "when I arrive" is not to the second. Switching
  Location off in Android's settings drops the fences; open Settings → Location in the app to put
  them back. Places changed on another screen reach this phone when Settings is next opened.
- arm64 phones only (the WebRTC libraries are large).
