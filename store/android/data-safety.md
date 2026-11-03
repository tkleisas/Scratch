# Google Play Data safety answers

Use these answers in Play Console → App content → Data safety.

- **Does your app collect or share any of the required user data types?** No.
- **Is all of the user data collected by your app encrypted in transit?** Not applicable — no data is collected.
- **Do you provide a way for users to request that their data is deleted?** Not applicable — no data is collected. Local app data can be removed by clearing app storage or uninstalling.
- **Data types collected:** None.
- **Data types shared:** None.
- **Data processed ephemerally:** Local audio library, queue, EQ/FX state and PCM cache are processed on-device only. They are not sent off-device by Scratch.
- **Ads:** No.
- **In-app purchases:** No.
- **Account creation:** No.
- **Analytics / crash reporting / tracking SDKs:** None.

## Permissions

- `READ_MEDIA_AUDIO` (Android 13+): read local audio files chosen for playback.
- `READ_EXTERNAL_STORAGE` (Android 12 and below): read local audio files for playback.
- No internet, location, camera, microphone, contacts or photo permissions.

## Store declarations

- **Ads:** No ads.
- **App access:** No login or restricted access.
- **Content rating:** Music/audio tool; no user-generated content, gambling, violence, or social features.
- **Target audience:** General music users; not directed at children under 13.
- **News app:** No.
- **Government app:** No.
- **Financial features:** No.
- **Health features:** No.
