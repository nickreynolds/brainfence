# Releasing

How to build signed releases and publish them. Today that's Android on Google
Play; Windows and macOS distribution (signing, notarization, auto-update) is
still to do.

## Website (brainfence.online)

`site/` is the public website: the home page, `privacy/` and
`delete-account/`. Google Play links to the last two, so keep them in step
with the apps (`store/play/data-safety.md`).

It's served by the Caddy on the PowerSync droplet. `npm run deploy` uploads it
(`--skip-site` to leave it out). First time only:

1. DNS (Namecheap → Advanced DNS): an **A record** for `@` pointing at the
   droplet's IP (`POWERSYNC_SSH_IP` in `.env`), and a **CNAME** for `www`
   pointing at `brainfence.online`. Leave the mail (MX, SPF) records alone.
2. Commit and push the `powersync/` changes, then on the droplet:
   `git -C /root/brainfence pull && cd /root/brainfence/powersync && docker compose -f docker-compose.prod.yml up -d caddy`.
   Caddy gets the HTTPS certificate on its own once DNS resolves.
3. `npm run deploy -- --skip-migrations --skip-auth --skip-sync-rules` uploads the site.

## Icons and store graphics

`npm run icons` regenerates every platform's app icon and the Play graphics
from the approved mark (branding/icons.mjs). Commit the output.

## Android

### The signing key (once)

Play and the website APK are signed with the same key, so users can move
between them, and every update must be signed with it. **If it's lost, no
installed copy can ever be updated.** Keep the keystore outside the repo and
back it up (password manager plus one other place).

```sh
keytool -genkeypair -v -keystore ~/keys/brainfence-release.jks \
  -alias brainfence -keyalg RSA -keysize 4096 -validity 10000
```

Then in `.env`:

```sh
ANDROID_KEYSTORE_PATH=/Users/you/keys/brainfence-release.jks
ANDROID_KEYSTORE_PASSWORD=…
ANDROID_KEY_ALIAS=brainfence
ANDROID_KEY_PASSWORD=…
```

### Building a release

1. Raise `versionCode` (and `versionName`) in `android/app/build.gradle.kts`.
   Play rejects a bundle whose `versionCode` it has seen before.
2. `cd android && ./gradlew :app:bundleRelease`
3. Upload `android/app/build/outputs/bundle/release/app-release.aab`.

For the website APK: `./gradlew :app:assembleRelease`
(`app/build/outputs/apk/release/app-release.apk`).

### Google Play, first time

1. Finish identity and device verification in Play Console.
2. **Create app**: Brainfence, English, App, Free (it can never become paid
   later), and accept the declarations.
3. **The package name `dev.brainfence` is permanent** once a bundle is uploaded.
4. On the first upload, Play asks about app signing: choose to **use your own
   key** (export and upload a key from a Java keystore) and follow its PEPK
   steps with the keystore above, so Play and website builds share a signature.
5. **Internal testing** first: add testers' emails, upload the bundle, send
   them the opt-in link. No 14-day clock and no long review, so use it to catch
   problems.
6. Fill in Policy → App content from `store/play/data-safety.md` and
   `store/play/permissions.md` (record the declaration videos), and the store
   listing from `store/play/listing.md`.
7. **Closed testing**: at least 12 testers (aim for 15–20) opted in for 14 days
   in a row. Then apply for production access.
