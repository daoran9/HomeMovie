# DMM Page Fixtures

Captured on 2026-09-08 through the test device using `age_check_done=1`.
Mobile requests use the Android Chrome UA in DmmScraper; desktop uses Chrome 120 on Windows.

- `dvd-mobile.html`: `/mono/dvd/-/detail/=/cid=118onet012/`
- `dvd-desktop.html`: same DVD URL with desktop UA
- `rental-mobile.html`: `/rental/ppr/-/detail/=/cid=118onet012r/`
- `search-mobile.html`: `/search/=/searchstr=onet-012/limit=30/sort=rankprofile`

Retained product headings, canonical/meta tags, overview, product fields, comments and rating
elements from the real responses. Scripts, surrounding navigation and recommendations were
removed and jsoup serialized the retained DOM. The search fixture retains both editions.
Unmodified full responses remain in the local `.codex/dmm-page-validation-20260908/` folder.
Tests also accept `dmm.captureDir` to replay those complete responses without DOM reduction.

DVD release is 2016-12-16. Rental availability is 2017-02-16 and is deliberately not written
as the original release date. Both editions report runtime 130 and rating 4.43.

`dvd-keywords-mobile.html` was captured on 2026-09-12 from
`https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=h_955kv302/` using DmmScraper's mobile UA.
The retained DOM includes `section.area-keyword` with 10 links and 8 distinct tags.
The complete response is stored locally alongside the earlier captures and can be replayed
with the same `dmm.captureDir` override. Inline event attributes are inert fixture data.

## Same-work Candidate Tags

`candidates/` contains reduced DOM from the 2026-09-12 BONY-062 and BONY-073
all-category search, original DVD and reissue DVD responses. The `7bony` links
were supplied in the user's current `D:/Desktop/1.docx`; this is a newer document,
not the earlier AWTN-003 evidence at the same path. Requests used the scraper UA,
language header and age cookie through the existing local proxy.

The original DVDs rank higher but have no related tags. The reissue DVDs each
have 11 tags and share the original's canonical digital work ID. Reissue dates,
label, title markers and outlet genres differ and must not replace primary fields.
Only independent tags supplement the primary; reviewed commercial terms and a
different candidate label are excluded. Repeated search links produce one request.

Full HTML remains in `.codex/device-readonly-batch5-rescrape-20260912-2145/` with
the same filenames. Set `dmm.candidateCaptureDir` to that directory to replay
the complete responses in `DmmCandidateTagsTest`. Reduced fixtures retain every
search detail link and the product's identity, headings, fields, comments and tags.
