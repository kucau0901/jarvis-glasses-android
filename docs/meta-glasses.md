# Setting up Ray-Ban Meta glasses

This is the part that takes the longest, and most of it happens in Meta's apps, not in this one.
Follow the steps in order. Each step says why it is there: almost every one was found the hard way.

What it gets you: **"Hey Meta, open Jarvis"** opens Jarvis on the glasses, with nothing to tap on
the frame, and the app can use the glasses' microphone, speakers and camera.

What works without any of this: with the glasses simply paired to the phone over Bluetooth, the
app already uses them as a headset (Talk in the app, alerts spoken in the glasses). The camera and
"Hey Meta" need the steps below.

## What you need

- Ray-Ban Meta (or Oakley Meta) glasses, paired with the **Meta AI** app on an Android phone
  (Android 12 or newer, 64-bit). Meta AI version 254 or newer.
- A Meta account, the same one the glasses and Meta AI use. In a country where Meta's AI glasses
  are sold, so the Wearables Developer Center lets you in.
- This app built from source (README, "Build and install"), so you can put your own project's
  IDs into it.

## Why not Developer Mode

Meta AI has a **Developer Mode** that lets any app connect to the glasses with no project at all.
It is enough for the camera and audio, but **on Android, "Hey Meta, open …" never reaches an app
in Developer Mode**: Meta AI answers "Something went wrong" or "I can't open the app". Meta's
maintainers say voice launch on Android is tested through a **release channel** (a Beta channel
is fine). So the setup below makes a private Beta release of Jarvis for your own account, and
Developer Mode is used for one thing only (step 5).

How Meta knows which "Jarvis" is yours: the name is matched only against apps installed on *your*
glasses, through a channel you joined. Another developer's "Jarvis" never reaches you.

## 1. Find your app's signing fingerprint

The Developer Center asks for the SHA-256 fingerprint of the key that signs the app. A debug build
is signed with your computer's debug key:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA256
```

Copy the `SHA256:` value. If you build on another computer, its debug key differs: add that
fingerprint too, or the glasses will refuse the app.

## 2. Make the project in the Wearables Developer Center

At [wearables.developer.meta.com](https://wearables.developer.meta.com), signed in with your Meta
account (accept the developer terms the first time):

1. **Create a project** (in an organization; make one if asked). Call it Jarvis.
2. **Add an Android app** to it:
   - Package name: `app.jarvis.glasses`
   - Signature: the SHA-256 from step 1
   - URL scheme (where Meta AI returns after connecting): `jarvisglasses`
3. **App name: `Jarvis`.** This is the word Meta's assistant listens for. The field may come
   prefilled; check after saving that it reads exactly "Jarvis" and not "JarvisJarvis".
4. **Permissions:** switch on **Camera** and **Microphone**, each with a one-line reason
   ("to answer questions about what you are looking at", "to talk to Jarvis"). Save, and reload to
   check they stayed on: an unsaved switch is easy to miss.
5. **Create a version** (1.0.0). Meta builds it; it shows **In Progress**, then **Ready** after
   about 15 minutes. A version carries the permissions as they were when it was made, so change
   a permission, then make a new version.

## 3. Put the version on a Beta channel and invite yourself

1. In the project, **Distribute → Beta** (make the channel if there isn't one). Assign the
   **Ready** version to it.
2. **Users → invite** your own Meta account's email (the account on the glasses).
3. **Accept the invite** from Meta's email, or at
   [wearables.meta.com/invites](https://wearables.meta.com/invites).

Each time you make a new version, assign it to Beta again; your invite carries over.

## 4. Put the project's IDs into the app

In the Developer Center, **Jarvis → Configuration** shows two lines to put in an app's manifest:

```xml
<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="1234567890123456" />
<meta-data android:name="com.meta.wearable.mwdat.CLIENT_TOKEN" android:value="AR|1234567890123456|abcdef…" />
```

Don't edit the manifest: put the two values in `local.properties` at the top of this repository
(it is never committed):

```properties
mwdat_application_id=1234567890123456
mwdat_client_token=AR|1234567890123456|abcdef…
```

One line each, no quotes, no spaces around `=`. The client token is a credential: keep it out of
chats, commits and screenshots. Then build and install again (`./gradlew installDebug`).

The build passes both as text on purpose: Android reads a number-only Application ID in the
manifest as a number, and the toolkit then sees no ID at all
([meta-wearables-dat-android#80](https://github.com/facebook/meta-wearables-dat-android/issues/80)).
Without these values the app runs in Developer Mode (`0`).

## 5. Install Meta's toolkit on the glasses, then switch Developer Mode off

The glasses need Meta's Device Access Toolkit installed on them for an app to be opened by voice,
and Meta AI only offers to install it while Developer Mode is on:

1. Meta AI → your glasses → **Settings → App info** → switch **Developer Mode** on.
2. On the same page, **DAT SDK version** says "Not installed": press **Install**, glasses on and
   connected. It then shows a version (1.0.0.0.0 or newer).
3. Switch **Developer Mode off**. The switch and the toolkit row disappear; that is expected.

Leaving Developer Mode on makes Meta AI register Jarvis as a "Developer mode app" again, which
can't be opened by voice (step 7 shows how to tell).

Developer Mode can also switch itself back on after a firmware or Meta AI update. If "Hey Meta"
stops working one day, check here first.

## 6. Check the glasses' language

Meta AI → your glasses → **Language and voice**: the assistant's language should be **English**.
Meta has only tested voice launch in English. The phone's own display language doesn't matter.

## 7. Connect Jarvis to Meta AI

In the Jarvis app, **Settings → Glasses → Connect Meta AI**:

1. Meta AI opens and asks to connect Jarvis. The consent screen should say
   **"Release channel: Beta 1.x.x"**. If it says **"Unverified app"**, Developer Mode is still on
   (step 5) or the IDs didn't reach the build (step 4): stop and fix that first.
2. Accept. Meta AI shows "Updating … Transferring to glasses" while it installs Jarvis onto the
   glasses.
3. Back in Jarvis, **Settings → Glasses** reads **Meta AI: connected**, *without*
   "(developer mode)".
4. Press **Allow camera** and choose **Always allow**.

If Connect times out (Meta AI can be slow fetching the Beta version the first time), press it
again.

If Jarvis was connected before in Developer Mode, that registration has to go first: press
**Disconnect Meta AI** in Jarvis, and if Meta AI → Settings → Apps still lists Jarvis under
"Developer mode apps", open it there and **Disconnect Jarvis**. Then connect again.

## 8. Turn on "Launch with Hey Meta"

Meta AI → **Settings → Apps → Jarvis** (now under **Connected**, on Beta):

- **Launch with "Hey Meta": on.** Its hint gives the exact phrase: *"Say 'Hey Meta, open Jarvis'
  to launch this app on your glasses."*
- **Camera: Always allow. Microphone: Always allow.**

If the switch isn't there yet, close Meta AI completely and open it again. The setting can take
15 minutes (sometimes an hour) to reach the glasses.

## 9. Try it

Glasses on, phone nearby (it can be locked; keep Jarvis allowed to run in the background):

1. Say **"Hey Meta, open Jarvis"**.
2. A short beep in the glasses means Jarvis is listening (in live voice there is no beep: just
   talk). Ask something: "What's on my calendar today?", or "What am I looking at?" for the camera.
3. Ask a follow-up straight away; no need to say "Hey Meta" again.
4. Say **"thanks"** or "that's all" (or "terima kasih", "dah") to end, or just stay quiet.

## When it doesn't work

| What happens | Why, and what to do |
| --- | --- |
| Meta AI: "Something went wrong" | Jarvis is still a Developer Mode app, or has no Beta version. Steps 3–5, then 7. |
| Meta AI: "I can't open the app" | Meta AI knows the name but can't launch it: Developer Mode still on (step 5), or the toolkit isn't installed on the glasses. |
| Jarvis opens but hears nothing | Microphone not allowed for Jarvis in Meta AI (step 8), or not in the version on Beta (step 2.4: make a new version). |
| "What am I looking at?" gets no photo | Camera not allowed: Allow camera in Jarvis, and Always allow in Meta AI. |
| Settings says "connected (developer mode)" | Disconnect, switch Developer Mode off, connect again (step 7). |
| Consent screen says "Unverified app" | The build has no project IDs (step 4), or Developer Mode is on. |
| It worked, then stopped after an update | Developer Mode switched itself back on, or a new Meta AI needs the toolkit reinstalled (step 5). |
| Connect times out | Press Connect Meta AI again. |
| "Headset: not connected" | The glasses aren't connected to the phone over Bluetooth (folded, off, or out of range). Only a headset named like Meta glasses ("Ray-Ban", "Meta", "Oakley") counts. |

`adb logcat -s GlassesLink GlassesAudio LiveVoice` shows what the app sees: whether the toolkit is in
Developer Mode, how many glasses it sees, whether the "Hey Meta" listener is running, and every
turn's audio.
