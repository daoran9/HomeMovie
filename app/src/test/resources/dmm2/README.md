# DMM2 Detail Fixture

`anav00002-detail.json` is the full GraphQL response captured on 2026-09-09
using the production `Dmm2Scraper.DETAIL_QUERY` and CID `anav00002`.
The response has no GraphQL errors and its returned ID matches the request.

The official frontend's `SampleMovieUrl` query reads
`ppvContent.sample2DMovie { highestMovieUrl hlsMovieUrl }` and
`ppvContent.sampleVRMovie { highestMovieUrl }`.
Source: https://assets.video.dmm.co.jp/_next/static/chunks/5418-070c205d412b0065-pc-20260908110632-b09387f.js

Tests replay the full detail through the public scraper, including empty search
and direct CID lookup, and check the written NFO trailer. HLS-only, absent/null
samples and VR-only cases are explicit synthetic mutations of the fixture.
The saved URLs are parsing evidence, not a guarantee of perpetual availability.
