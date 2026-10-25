# Bilibili VOD request and CDN candidate contract

## Evidence and final behavior

Public anonymous requests on 2026-10-25 established two distinct failures and a successful complete reference baseline. No account login, Cookie header, TLS change, proxy change, or remote write was used in these probes. Signed URLs and original response bodies were not persisted.

1. With the original Mobile UA/page Referer API contract, view returned HTTP200/code0 and nav HTTP200/code-101, but signed playurl returned HTTP412/code-412 on both host and phone. Changing only the API Referer to `https://www.bilibili.com/` produced playurl HTTP200/code0. This fixed API access, not CDN playback.
2. Subsequent video CDN requests using Mobile/page headers returned HTTP403; some P2P primaries timed out. Audio ranges succeeded. Non-P2P backup `upos-sz-mirrorhwo1.bilivideo.com` also returned HTTP403 under the incomplete media contract. Video/audio oi, deadline and uipk values were present and equal; no values were logged. Error responses exposed no useful structured error code.
3. Complete reference baseline on BV1GJ411x7h7: API and CDN both use Desktop Chrome153 UA plus root Referer. Select an API-provided non-P2P AVC backup. The single video Range request returned HTTP206 with 1024 bytes. This is a successful combined baseline, not proof that one of the jointly changed media headers alone explains the fix.

Production now follows that complete VOD baseline for both ordinary video and PGC. Exact VOD UA:

```text
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36
```

Main-site HTTPS API requests and returned VOD playback headers use `Referer: https://www.bilibili.com/`. Canonical page and episode URLs remain specific, including selected `p`, so playback identity is not confused with the HTTP Referer.

## Candidate selection

Each VOD video, audio and progressive asset combines its original URL with API-provided `backupUrl` / `backup_url` candidates. Array and single-string backup forms are supported. Candidates are stably ordered with non-P2P first; remaining candidates are retained. No host, path or signed query is synthesized or rewritten. Repeated identical URLs are deduplicated, preserving the supplied URL set.

P2P detection uses the `os=mcdn` query field and known host patterns, including mcdn domains, 302 first-label markers and the reference's identified peer hosts. An arbitrary edge hostname carrying `os=mcdn` is recognized even if the hostname itself is unfamiliar. When every candidate is P2P, the supplied original order remains available rather than failing resolution.

## Live isolation

Live retains the already verified Android13/Chrome120 Mobile UA and room-specific API/media Referers through its separate `LIVE_USER_AGENT` constant. VOD asset ordering does not alter live URL construction or DVR metadata. Main API cookies remain exact-host restricted and are never returned to the media engine.

## Multipart fixture follow-up

The complete Desktop/root contract was independently checked with phone curl for public BV17x411w7KC, which still returned ten pages:

- P1, cid279786, title Хоп, duration199 seconds: selected non-P2P AVC video and audio each returned HTTP206/1024 bytes.
- P2, cid275431, title Imash li surce, duration205 seconds: non-P2P AVC returned HTTP206/1024 bytes. The first audio request timed out; a separately resolved API-provided alternate non-P2P audio candidate returned HTTP206/1024 bytes. The timeout remains in the private evidence and is not erased by the successful follow-up.

Both pages have confirmed bounded video/audio candidates. No complete videos were downloaded by those bounded curl probes. Subsequent Lead app validation on Redmi K20 Pro / Android15 passed actual P1/P2 decoding, paused episode switching and seek, and resumed frame/content progression. The separate BV1GJ411x7h7 case passed real quality and audio-format changes while paused and playing, with position and other-track preservation. This app evidence is distinct from the 1KB HTTP probe evidence. Private summaries retain original failures and follow-up attempts without saving signed URLs.

Audio labels now use returned codec information (AAC, HE-AAC, FLAC or Dolby) and available bandwidth in kbps, falling back to a human-facing track ordinal when codec metadata is absent. Stable audio IDs and default selection order remain unchanged; the integration test asserts an AAC/128kbps label and the original audio ID.

## Tests and verification ownership

`BilibiliCdnCandidatesTest` covers non-P2P preference, hidden os markers, known host detection, signed literal and candidate-set preservation, stable ordering, and all-P2P retention.

`BilibiliMediaResolverTest` covers video/audio integration with independent candidate lists and a string backup, exact Desktop/origin main API headers, VOD/PGC playback headers, unchanged canonical identities, and unchanged exact Mobile/room Live headers.

The Lead coordinates Gradle, APK and actual decoding verification. The resolver owner does not claim those checks passed from a successful 1KB HTTP Range probe. Private status-only evidence retains original failures and the successful baseline separately; no secret URL artifacts are part of the source tests.
