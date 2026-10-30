# Changelog

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
