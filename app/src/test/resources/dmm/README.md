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
