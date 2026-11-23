# Changelog

## 1.1.0 (2026-11-24)

AirPlay live streams now play on the TV. Everything else is unchanged from 1.0.0.

### Fixed - AirPlay video (HLS), notably YouTube live

- Live streams play with audio: master-playlist URI parsing now includes the
  `#EXT-X-MEDIA` audio renditions, which are requested over the AirPlay reverse
  channel and advertised to the player once the sender delivers them.
- YouTube's condensed media playlists (`#YT-EXT-CONDENSED-URL`) are expanded to
  full segment URLs, including CRLF payloads and interleaved discontinuity tags.
- Quality changes no longer stall or fall back to the previous stream: the
  newest playback session is always the one served to the player.
- Live playback starts at the live edge; the LIVE button and the seek bar agree
  with the sender's timeline.
- Live playback rejoins automatically after transient segment errors instead of
  dropping the session.
- Sender `setProperty` requests (quality/track selection) are handled safely
  when no playback session is active.

### Notes

- Starting a live stream may briefly buffer while the player builds its cushion
  at the live edge; playback then continues without interruption. This is
  expected over WAN links and matches how live players generally behave.
- Native engine (`libairplay_native.so`) rebuilt with the fixes above.
