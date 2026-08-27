# Build the APK online — no PC, no Android Studio

GitHub builds it for you on their servers, free. You can do all of this
from your phone's browser.

## Steps

1. Create a free account at **github.com**.
2. Click **+ → New repository**. Name it `maqam-tashreeq`. Keep it **Public**
   (free Actions minutes are unlimited for public repos). Create it.
3. On the new repo page click **uploading an existing file**.
4. Unzip `MaqamTashreeq-android.zip` and upload **everything inside the
   `MaqamAPK` folder** — keep the folder structure. Commit.
5. Open the **Actions** tab. The build starts on its own.
   (If it asks to enable workflows, click the green button.)
6. Wait about 3–5 minutes for the green tick.
7. Download the APK from either place:
   - **Actions → the finished run → Artifacts → MaqamTashreeq-APK**
   - **Releases** (right sidebar) → the newest build → `MaqamTashreeq-debug.apk`

## Install on the phone

Open the downloaded `.apk`. Android asks permission to install from this
source — allow it once, then Install.

## Updating later

Edit `app/src/main/assets/index.html` directly on GitHub (pencil icon) and
commit. A new APK builds automatically within minutes.

## Notes

- This produces a **debug-signed** APK. It installs and runs perfectly for
  personal use, but Google Play requires a release-signed build.
- The artifact is kept for 90 days; the Release copy is permanent.

## Other free online builders

| Service | Notes |
|---|---|
| GitHub Actions | Recommended — free, unlimited for public repos |
| Codemagic | Free tier, 500 min/month, good Android support |
| Google Cloud Shell | Free Linux VM in the browser, install the SDK yourself |
| AppCircle | Free tier, simple UI |
