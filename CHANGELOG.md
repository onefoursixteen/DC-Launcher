# Changelog

## v1.2.0 — 2026-11-08

### New
- **TMDB watchlist sync** (Settings → Spotlight): connect your TMDB account by scanning a QR
  code and approving it on themoviedb.org (your password is never typed on the TV). Titles
  added or removed on either side show up on the other.

### Fixed
- The Web Admin address now shows the TV's home-network IP even while a VPN is connected.
- Picking a file that isn't a picture no longer leaves the home screen without a background.
- Wallpaper URLs with stray spaces are accepted.
- Old background files are cleaned up whichever way the new one was set.
- Settings (including the TMDB key and account) are excluded from cloud backup and device transfer.

### Cleaned up
- Removed unused code, imports and leftover design mock-ups, and the debug-only weather preview screen.

### Install
Updates v1.0.0 or v1.1.0 in place and keeps your settings: install `dc-launcher-v1.2.0.apk` over it.

## v1.1.0 — 2026-11-08

### New
- **Dock layout** (Settings → Appearance → Home layout): your apps in one frosted-glass row
  with their own logos and names. Up to six apps fill the row; more scroll, with soft faded
  edges. The panel, focus highlight and logos share the same rounded corners.
- **Web Admin** has its own settings menu item. Switching it on shows a QR code in the middle
  of the screen, with the address and PIN underneath; scan it with your phone's camera.
- **Upload a background from your phone or computer** on the web page (JPG, PNG or WebP, up
  to 20 MB). It appears on the TV straight away, and you can go back to the default gradient.

### Fixed
- The first app is now always highlighted when the home screen opens, and Home brings the
  dock back to its first app.
- The app list is refreshed every time the home screen appears, so apps can't go missing
  after a restart.

### Install
Updates v1.0.0 in place and keeps your settings: install `dc-launcher-v1.1.0.apk` over it.

## v1.0.0 — 2026-10-30

First release of **DC Launcher**: a lightweight, ad-free home screen for Android TV and
Google TV.

### Home screen
- Clock and date, animated day/night weather icon, and a network button that turns into a
  shield while a VPN is connected.
- Apps in a two-row window: going past the second row slides the rows up, while the clock,
  spotlight and tabs stay put.
- Tabs: Favorites, Watchlist, All apps, and your own categories.
- Hold OK (or press Menu) on an app to favorite, move, categorise, hide, see app info, or
  uninstall it.

### Spotlight and watchlist (optional, needs a free TMDB key)
- This week's genuinely new movies, series and season premieres, with artwork, in your
  weather city's region, and the streaming app that has each one.
- "Only show titles on my apps" and "Not interested" filters.
- A watchlist that checks daily where each title streams and when the next episode airs,
  and moves titles to the front of the spotlight once they're watchable.

### Settings and tools
- Wallpapers (gradients, colours, images from a URL or a file), grid size, clock format,
  weather city and units.
- A web admin page protected by a PIN shown on the TV, for entering the TMDB key, searching
  titles and managing the watchlist from a phone or computer. It turns itself off after 15
  minutes or 5 wrong PINs.
- Remap the remote's Assistant button to open any app.

### Privacy
- No ads, analytics, crash reporting or tracking.
- Network use is limited to the weather (Open-Meteo), the spotlight and watchlist (TMDB),
  and URL wallpapers you set up yourself.

### Install
Sideload `dc-launcher-v1.0.0.apk`. Making it the home screen on Google TV needs ADB; see
the README's setup guide.

> This product uses the TMDB API but is not endorsed or certified by TMDB.
