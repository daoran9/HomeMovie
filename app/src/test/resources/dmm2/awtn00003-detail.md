# AWTN-003 Classification Fixture

Captured on 2026-09-09 from `https://api.video.dmm.co.jp/graphql` using the production
`Dmm2Scraper.DETAIL_QUERY`, operation `Test`, variables `{"id":"awtn00003"}`.
The adjacent JSON is the unmodified response. It has no GraphQL errors and returns
the requested CID. Referer: `https://video.dmm.co.jp/av/content/?id=awtn00003`.

The user identified `D:/Desktop/1.docx` as AWTN-003 evidence only. Its 5 genre links
use `genre`, and its 16 related-tag links use `tag` with `dmmref=awtn00003`.
The document includes 7 two-tag groups and 9 individual links, totaling 13 unique
tag names. Each document tag ID/name is present in the captured response.

The request uses `relatedTags(limit: 50)` and returned 26 items (7 groups and
19 individual tags), totaling 22 distinct names. The website's display limit is
not established. Additional returned tags are source data, not a claim that the
document displays them. Group members are flattened into individual NFO tags;
group relationships are retained in this fixture, not encoded as compound tags.

Search `keywords` are not detail `relatedTags` and must not supply NFO tags.
Independent tags remain valid even when also present in `genres`. Existing
reviewed name normalization and technical-tag placement remain unchanged:
the source's 5 genres become 4 content genres plus the technical tag for HD.
The resulting official-only NFO has 23 tags (22 related tags plus HD).

This fixture proves AWTN-003 source parsing and offline classification flow only.
It does not prove device writeback, UI behavior, all fields, or other movies.
