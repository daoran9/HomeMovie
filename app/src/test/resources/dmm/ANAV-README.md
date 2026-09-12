# ANAV-002 route regression

Full search and detail-shell responses were captured read-only on 2026-09-09
at 12:22 +08:00 using device system curl with the application headers.
They are new reproductions, not the original failed 12:06 response.

- anav-search.html: https://www.dmm.co.jp/search/=/searchstr=ANAV%20002/limit=30/sort=rankprofile
- anav-client-shell.html: https://video.dmm.co.jp/av/content/?id=anav00002&i3_ref=search&i3_ord=2

The production selector must choose anav00002. The client-rendered shell has
no h1, og:title or product body. Production routing must use the structured
detail reader instead. Its full JSON fixture is ../dmm2/anav00002-detail.json.
Tests do not make network requests or execute scripts in these responses.
