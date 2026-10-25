# WBI test-vector verification

Verified 2026-10-25 by the resolver task owner, without an application build or device operation.

## Independent fixed-vector calculation

The test's image-key permutation produces `ea1db124af3c7062474693fa704f4ff8` (the existing key assertion passed). The sorted query is `bar=514&baz=1919810&foo=114&wts=1702204169`.

A separate PowerShell/.NET `System.Security.Cryptography.MD5` calculation over the UTF-8 bytes of the exact literal below returned `6149fdadf571698ca7e6a567265cd0ee`:

```text
bar=514&baz=1919810&foo=114&wts=1702204169ea1db124af3c7062474693fa704f4ff8
```

Thus the previous expected digest `8f6f2b5b3d485fe1886cec6a0be8c5d4` was incorrect for these inputs. The Kotlin production algorithm was unchanged; the test expectation was corrected and renamed to avoid claiming a verified published provenance. Online documentation lookup was unavailable in the web tools (DNS non-public-address rejection; search credentials absent).

## Independent anonymous API request

A separate PowerShell implementation fetched current public WBI image keys from `https://api.bilibili.com/x/web-interface/nav`, fetched metadata for public BV17x411w7KC from `https://api.bilibili.com/x/web-interface/view`, then sent a signed GET to `https://api.bilibili.com/x/player/wbi/playurl`. It used browser User-Agent and Bilibili Referer, no Cookie header, no persisted HTTP session, and no login or remote writes.

Observed output:

```text
Anonymous nav API code=-101; signed play API code=0
Actual video qualities=16; audio streams=1
Probe issued GET requests only, without Cookie; signed URLs and API bodies omitted.
```

This confirms that the independently generated signed request was accepted and anonymous playback returned actual quality 16. It does not assert signature enforcement against invalid signatures, account entitlements, media decodability, or Android playback. Current signing keys, complete signed request/CDN URLs, and response bodies were not recorded.

The Lead owns the subsequent unified unit-test rerun. No test pass is claimed for the corrected source until that rerun completes.
