# Babkin Receptár

Android recipe app for grandma (Slovak UI, huge text, vegetarian). Thin Java WebView shell; the UI is
`app/src/main/assets/www/`. Builds run on the devbox only.

## Add or change recipes (no reinstall needed)
1. Edit `recipes/*.json` (one object per recipe: id, name, emoji, category, servings, time,
   ingredients[], steps[], optional note; a line starting with `# ` is a sub-heading).
2. `python3 tools-build-recipes.py <N>` with N higher than the last published version.
3. On the devbox: copy `recepty.data` to `~/downloadables/babkin-receptar/recepty.data`.
   Phones fetch https://devbox.tail20cc8f.ts.net/apps/f/babkin-receptar/recepty.data on every start
   and keep the newest version. (The store refuses .json files, hence `.data`.)

## Ship a new app version
1. Bump `versionCode`/`versionName` in `app/build.gradle`.
2. On the devbox: `BABKIN_SIGNING=~/keys/babkin-receptar/signing.properties ./gradlew assembleRelease`
3. Copy the APK to `~/downloadables/babkin-receptar/babkin-receptar-<version>-universal.apk` and update
   `meta.json` there (`version`, `versionCode`, `sha256: {"<apk name>": "<hash>"}`, `whatsNew`).
   Installed apps see the new versionCode in `/apps/api/apps` and show an "Aktualizovať" banner.

Signing key: one key for life, never regenerate it (Android refuses updates signed with another key).
