# Self-hosted fonts

The setup wizard's "Hello" moment uses **Homemade Apple** (Apache 2.0, by
Font Diner) for that handwritten feel. We self-host it so a fresh Picsou
install makes zero outbound requests to Google Fonts or any CDN — aligned
with the project's privacy/OSS posture.

## What to drop here

`HomemadeApple-Regular.ttf` — self-hosted font file. Fetch once and commit:

```bash
# From https://fonts.google.com/specimen/Homemade+Apple/license
# Download the family zip, then copy the TTF:
cp HomemadeApple-Regular.ttf frontend/public/fonts/
```

## Graceful fallback

If the font file is missing, the CSS declares a fallback stack
(`'Segoe Script', 'Snell Roundhand', cursive`) so the wizard still renders
something legible — just less special. The page will NOT try to download
the font from Google as a backup; that would defeat the point.
