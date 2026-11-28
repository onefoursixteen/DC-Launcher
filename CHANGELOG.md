# Changelog

## v1.6.2 — 2026-11-28

### Changed
- **Frosted network panel:** opening the network/VPN panel frosts the home screen behind it,
  and the panel itself is 3D glass with a bright rim. (The blur needs Android 12 or newer;
  older TVs show a solid glass panel instead.)
- The round buttons in the top-right corner no longer cast a shadow.

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.6.2.apk` over it.

## v1.6.1 — 2026-11-28

### Changed
- **Bigger watchlist tiles:** Watchlist and Requests tiles are about 25% larger and still show
  two rows under the spotlight, so titles are easier to read on the TV.
- **Scrolling captions:** the text under the selected tile (watchlist status, app names in the
  grid and the dock) now scrolls sideways when it's too long to fit.
- **Glass buttons:** the round buttons in the top-right corner match the glass dock: clear,
  top-lit glass with a soft shadow, turning frosted white when selected.

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.6.1.apk` over it.

## v1.6.0 — 2026-11-28

### Changed
- **New dock look:** the Dock layout is now a raised glass panel, lit from the top with a bright
  rim and a soft shadow underneath. The focused app lifts up with a glossy highlight.
- With only a few apps in a tab (for example a single favourite), the dock shrinks around them
  and sits centred, so the app shows at its normal size instead of stretched across the screen.

### Fixed
- **Categories can be deleted again.** In Settings → Categories the row was wider than the
  screen, so *Move up* and *Delete* were cut off. All buttons now fit, and Delete asks before
  removing the category (the apps themselves stay installed).

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.6.0.apk` over it.

## v1.5.0 — 2026-11-28

### New
- **Requests tab** (when Seerr is set up): everything you've asked for in Seerr, with its
  status: *Waiting for approval*, *Downloading*, *In your library* or *Declined*. OK opens the
  details, with **Watch in Jellyfin** once it's in your library.
- **Just arrived:** when one of your requests lands in the library, its tile gets a
  *JUST ARRIVED* tag and the title moves to the front of the spotlight for 3 days.
- The TV checks your requests when the home screen appears (at most every 10 minutes) and every
  15 minutes while it stays on screen. The last list is kept, so the tab still shows with the
  VPN off; it updates the next time Seerr is reachable.

### Fixed
- Details of a requested title no longer say "Not on a subscription service" (streaming
  services aren't looked up for requests).

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.5.0.apk` over it.

## v1.4.2 — 2026-11-25

### Changed
- The Request button's hint now reads "Place a request in Seerr".

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.4.2.apk` over it.

## v1.4.1 — 2026-11-22

### Changed
- **Seerr behind a VPN.** Web Admin now only *saves* the Seerr address and your Jellyfin login,
  so it works with the VPN off. Turn the VPN on and press **Connect** in the new
  **Settings → Seerr** page. The password is stored encrypted with a key held in the TV's
  Android Keystore and is never shown on the web page.
- When Seerr ends the session, the TV signs in again by itself the next time Seerr is reachable.
- With Seerr out of reach, search and requests say "Can't reach Seerr. Is the VPN on?".

### Fixed
- The Settings menu scrolls, so Web Admin and About are no longer cut off at the bottom.

### Note
If you set up Seerr in v1.4.0, enter the Jellyfin password once more in Web Admin so the TV can
sign in again later.

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.4.1.apk` over it.

## v1.4.0 — 2026-11-22

### New
- **Seerr search and requests.** Sign in to your Seerr server with your **Jellyfin** account in
  Web Admin (only the Seerr session is kept on the TV, never the password). Then:
  - a 🔍 button on the home screen searches movies and series, with posters and
    *In library* / *Requested* / *Downloading* badges;
  - the details panel (search, spotlight and watchlist) shows the title's Seerr status and
    has **Request**, plus **Watch in Jellyfin** when it's already in your library;
  - series have a season picker (all missing seasons, the latest one, or pick your own);
  - requests are made as your Jellyfin user, so your Seerr limits and auto-approval apply;
  - Web Admin search results get a **Request** button too.
- Plain `http://` is allowed only for Seerr servers on your home network; internet
  addresses must use `https://`.

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.4.0.apk` over it.

## v1.3.1 — 2026-11-08

### Fixed
- **Watch trailer** now plays the trailer straight away instead of only opening YouTube.

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.3.1.apk` over it.

## v1.3.0 — 2026-11-08

### New
- **Watch trailer** in the details of spotlight and watchlist titles: plays the official
  trailer (in the TV's language when there is one) in the YouTube app, or in any app that
  opens YouTube links, such as SmartTube.

### Install
Updates any earlier version in place and keeps your settings: install `dc-launcher-v1.3.0.apk` over it.

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
