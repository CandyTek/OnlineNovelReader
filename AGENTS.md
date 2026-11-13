# AGENTS.md

Working notes for coding agents on **Novel Sniffer** (小说嗅探阅读器): an Android browser that recognises novel
pages on arbitrary sites, strips ads, and reads them in a native reader with a bookshelf and whole-book caching.
`README.md` describes the product and architecture for humans; this file is about how to change it safely.

## 1. Ground rules

- **Plain Java, framework views only.** No Kotlin, no AndroidX, no Compose, no view binding. Activities extend
  `android.app.Activity`; settings use the deprecated `PreferenceFragment` on purpose. Layouts are XML only for
  the big screens; dialogs, sheets and list rows are built in code.
- **One runtime dependency: jsoup.** Do not add libraries without asking. `org.json` and JUnit are test-only
  (Android provides `org.json` at runtime).
- **The owner wants the browser as feature-rich as possible.** When in doubt include the feature and say what
  was left out, rather than trimming.
- **No hard-coded websites (owner's rule).** Never add a host name, a per-site selector or a per-site branch
  to code or to `assets/rules.json` to make one site work. When a site fails, find the general property it
  violates (URL form, markup habit, navigation trick) and teach the heuristics that property, so every site
  built the same way works too. Built-in rules may only match by structural `signature` (a template shared by
  many clones), never by `host`. Host-keyed rules exist only as data: rules learned at run time and rules the
  user writes. Every such fix gets a `SnifferTest` case on invented markup with a made-up domain, not a copy of
  the real page.
- **Request budget (owner's rule).** Novel sites count requests per IP. The app may only make the requests
  the user directly asked for: the page in the browser, the chapter being turned to, a contents page the user
  refreshes or a book needs to open at all, and caching the user started. Anything else that touches the
  network (update checks, preloading, covers, suggestions, background refreshes, any future "nice to have")
  must sit behind a switch that **defaults to off**, in both `prefs.xml` and the `getBoolean(key, false)` call,
  with a manual alternative where it makes sense. See "Optional requests" in section 6.
- **Licence:** the repository carries a GPL-3.0 `LICENSE`. All code so far is original. Before copying code
  from another project, check that its licence is GPL-3.0-compatible and ask the owner.
- **Git:** branch `main`, no remote configured. Commit or push only when asked.
- **Two UI languages.** Every user-visible string goes into both `res/values/strings.xml` and
  `res/values-zh/strings.xml`. No hard-coded UI text in Java.
- Package `com.rc.novelreader`, minSdk 23, targetSdk 34, compileSdk 35. Guard newer APIs with
  `Build.VERSION.SDK_INT` (see `hideSystemBars`, `addToHomeScreen`, `Downloads.saveBytes`).

## 2. Traps on this machine (each has already cost time)

1. **Git Bash heredocs eat backslashes** (`\\` becomes `\`, even with `<<'EOF'`). Never write Java, JSON or
   regex text through a heredoc, and never drive a Python patch that contains `\\n` or regex escapes from one:
   the search string silently stops matching or the output is corrupted. Use the Edit/Write tools, or save the
   script to a file first. Heredocs are fine for text without backslashes.
2. **Full-width punctuation typed into source arrives as ASCII** (`（ ） ， ： ！ ？` and full-width digits).
   In Java write `\uFFxx` escapes, in XML `&#xFFxx;`. Ideographs and `。 、 【】 《》` survive. A regex class
   that was meant to contain full-width characters compiles fine and matches nothing, so test with escapes too.
3. **PowerShell `>` corrupts binary adb output.** Use `adb pull`, not `adb exec-out ... > file`.
4. **adb paths under Git Bash** get rewritten (`/sdcard/x` becomes `C:/Program Files/Git/sdcard/x`). Prefix
   with `MSYS_NO_PATHCONV=1`.
5. **Low memory.** Gradle is capped at `-Xmx1024m` in `gradle.properties`; leave it.
6. A method named `setImmersive(boolean)` clashes with `Activity.setImmersive`; similar framework names are
   easy to hit in `Activity` subclasses.

## 3. Build, test, install

Environment already set in the shell: `JAVA_HOME` = Android Studio JBR (JDK 21), `GRADLE_USER_HOME` =
`I:\android\.gradle`, SDK at `I:\AndroidSdk` (`local.properties`). Gradle 8.9 wrapper (Tencent mirror),
AGP 8.7.3, Maven through Aliyun mirrors.

```sh
./gradlew.bat :app:assembleDebug --console=plain -q        # app/build/outputs/apk/debug/NovelSniffer-debug.apk
./gradlew.bat :app:testDebugUnitTest --console=plain -q    # JVM tests, no device
adb -s 192.168.0.123:5555 install -r app/build/outputs/apk/debug/NovelSniffer-debug.apk
```

The noise to filter from build output is the `source value 8` / deprecation warnings; anything else is real.
Test results: `app/build/test-results/testDebugUnitTest/*.xml` (currently 35 tests in 5 classes).

## 4. Test device

- The owner's own phone is `192.168.0.208:5555` (Android 15, 1080x2400). It holds real reading data: never
  `pm clear` it, install only with `install -r`, do not unlock it if the screen is locked, and do not repeat
  notification text seen there. App data can be read without the UI:
  `adb exec-out run-as com.rc.novelreader cat databases/novel.db > novel.db` (also `shared_prefs/...xml`).
  `UIDRIVE_DEVICE=192.168.0.208:5555` points `tools/uidrive.py` at it.
- `192.168.0.123:5555` over TCP (`adb connect` first if it shows as not found). Always pass `-s`; other
  devices are often attached.
- AOSP arm64, Android 13, WebView 109, system locale Chinese, usually **landscape 1920x1080 at 160 dpi** (it was portrait 1080x1920 on 2026-11-12; run `wm size`): check wide
  layouts (sheets are capped at 520dp, the start page column at 560dp).
- Its home app is another app of the owner's (`com.jiuxin.scalp_detection`). When our browser finishes (Back
  with no history and no shelf underneath) that app appears. This is not a crash; confirm with
  `adb logcat -d -s AndroidRuntime:E`.
- The dev PC is `192.168.0.200` on the same LAN.
- Network is mainland China. Reachable: Bing, Baidu, `anti-ad.net`, GitHub API and codeload (github.com HTML
  is slow). Not reachable: Google, Wikisource, RoyalRoad. Real novel sites that work with a public-domain book:
  `https://www.zggdwx.com/xiyou.html`, `http://www.purepen.com/xyj/index.htm` (GB2312),
  `xiyouji.5000yan.com` (slow, sometimes times out).
- Leave the device clean when done: `pm clear com.rc.novelreader`, remove files you pushed, stop servers.

### Mock novel site

```sh
python tools/mock_site/gen.py <dir>          # N88=<n> sets the chapter count of book 88 (default 60)
python -m http.server 8765 --bind 0.0.0.0 --directory <dir>     # serve <dir> itself: links are root-relative
```

`python tools/mock_site/hijack_server.py` (port 8767, start at `/h/c1.html`) is a book without a contents page
whose site sells its navigation: the first request for chapters 2 and 5 is answered with a redirect to another
domain, and the first rendering of chapters 3 and 6 has that domain in the "next" link. It logs every hit, so
the retries (and the absence of preloading) can be read from its output.

Open `http://192.168.0.200:8765/` on the device. Contents:

| Path | What it exercises |
|---|---|
| `/` | portal home page that must **not** be detected |
| `/book/88/`, `/book/88/1001.html`... | GBK "biquge" template, ad banner, click-hijack overlay, scripted redirect to `casino-ads-example.com` after 2.5 s, chapters 5 and 6 split over pages |
| `/m/7/` | chapter list paginated over 3 pages |
| `/novel/glass-harbor/` | English, unnumbered titles, newest first |
| `/js/3/` | book and chapters rendered by JavaScript (needs the WebView fetcher) |
| `/solo/c1.html` .. `c8` | no contents page: linked mode |

### Driving the UI

`tools/uidrive.py` runs steps against the device and finds views by text, content description or resource id:

```sh
python tools/uidrive.py url:http://192.168.0.200:8765/book/88/ wait:2 dump:fab tap:btnMenu tap:页内查找 shot:out
```

Steps: `url:` (opens in the browser), `tap:`, `long:`, `xy:x,y`, `longxy:x,y`, `text:` (ASCII only),
`key:<code>` (4 = Back, 3 = Home, 66 = Enter), `wait:<s>`, `dump[:filter]`, `shot:<name>`.
Things that have gone wrong while driving:

- `uiautomator` reports WebView links that are scrolled off-screen with coordinates lying over our bottom bar;
  tapping them presses **our** Back/Home buttons. Keep test targets in the first screenful.
- One Back too many closes the browser; `dump` before acting when unsure which screen is up.
- The reader draws its own text (`PageView`), so it has no text nodes; Back in the reader raises an
  "add to bookshelf?" dialog for books not on the shelf.
- UI labels are Chinese on this device; resource ids (`btnMenu`, `fab`, `url`, `tabCount`, `siteIcon`,
  `btnStar`, `exitFull`, `findCount`) are stable.
- Geolocation, camera and microphone prompts never appear on plain HTTP (Chromium requires HTTPS).

### Site test record (debug builds)

Behind `BuildConfig.DEBUG` (`buildFeatures.buildConfig` is on for this), the browser menu ends with three extra
items: "Mark verified", "Mark failed" (tapping the active one again removes the mark) and "Test lists". The
verdict is per primary domain (`Urls.registrable`, so `m.` and `www.` share one entry), stored by `SiteTests`; a page of a marked site shows a green "Verified" or red "Failed"
badge in the top right corner (`BrowserActivity.refreshTestBadge`). It is a notebook for the owner's manual
testing only: it must never influence sniffing, fetching or ad blocking (that would be a hard-coded site list).

### Replaying real pages through the engine

In debuggable builds, "Detect novel" (识别小说) in the browser menu saves the rendered DOM to
`files/sniff/<time>.html` (first line `<!--URL:...-->`). Pull them and run
`SNIFF_DIR=<folder> ./gradlew.bat :app:testDebugUnitTest`; `RealPagesTest` writes `report.txt` there. Use this
to reproduce a misdetection in a unit test before changing heuristics.

## 5. Code map

```
App.java                 Application: UA (WebView UA minus "; wv"), MAIN handler, IO executor, SNIFFER singleton,
                         prefs(), toast(), dp()
util/Urls.java           resolve, normalize, host, registrable, sameSite, chapterKey, pageNo, sameChapter, fromInput
util/Texts.java          chapter-title patterns (STRONG/WEAK), Chinese numerals, wlen (CJK = 2)
util/BookParser.java     pure Java: text file -> chapters, EPUB (zip + jsoup) -> chapters
util/Hanzi.java          pure Java: simplified/traditional conversion from assets/hanzi.txt

sniff/                   PURE JAVA, no android.* imports: runs in JVM tests
  Sniffer.java           analyze(url, html) -> PageResult; learns per-host rules from confident heuristics
  RuleEngine.java        picks a SiteRule: user > learned > built-in, by host regex and/or signature selector
  SiteRule.java          rule model + JSON; selector syntax "css@attr"
  HeuristicEngine.java   main-text block, nav links, chapter list, scores (the largest and most delicate file)
  ContentCleaner.java    ad/watermark line removal, hard-wrap reflow
  FillerFilter.java      author notes / promotion at the two ends of a chapter (applied by the reader, not stored)
  PageResult.java        Type NONE/DETAIL/CHAPTER, BookInfo, ChapterRef, scores, selectors chosen
  Dom.java               jsoup helpers

net/Http.java            HttpURLConnection: manual redirects, CookieManager cookies, charset sniffing -> GB18030,
                         6 MB cap. get() for HTML, getBytes() for binaries
net/WebFetcher.java      off-screen WebView fetch for JS-rendered pages; decodeJs() unquotes evaluateJavascript results
net/PageFetcher.java     HTTP first, WebView fallback

browser/
  BrowserActivity.java   tabs list, chrome (bars, start page, suggestions, find bar), menu and tab sheets,
                         long-press menu, permissions, fullscreen, clear data, reading-mode entry
  Tab.java               one tab: lazy WebView, WebViewClient/ChromeClient, per-page state, sniff scheduling
  AdBlocker.java         host lists, request/navigation decisions, cosmetic script, list subscription
  Downloads.java         DownloadManager enqueue, data: URLs, file names
  LibraryActivity.java   bookmarks / history / downloads / offline pages, bookmark import-export
  UserScripts.java       user scripts: storage, header parsing, URL matching
  SiteTests.java         debug builds only: the owner's hand-test verdict per primary domain (prefs file "site_tests")
  Favicons.java          one site icon per host (files/favicons/<host>.png + memory cache), fed only by
                         WebView's onReceivedIcon; used by bookmarks, history, start tiles, suggestions, tab list

reader/                  PageView (own typesetting: StaticLayout per paragraph, 3 chapter slots, modes
                         COVER/SLIDE/NONE/SCROLL, tap grid, text selection, highlight), ChapterLayout,
                         ReaderActivity (menus, TOC drawer, overlays), ReaderSheets (every dialog behind "More"),
                         ReaderPrefs (all settings + themes), Backgrounds (generated paper textures, pictures),
                         TextRules (user replacements), Speaker + SpeakService (text-to-speech, system
                         engine or a web service), ReaderTools (dialogs: notes, statistics, word list, look-up,
                         AI, gestures, speed reading), Notes, Stats, Vocab, Ai (the optional assistant's HTTP)
shelf/                   ShelfActivity (launcher), BookSheet, Covers/CoverView, DownloadService (book caching)
data/                    Db (SQLite), Library (book logic), RuleStore (rules + novel hosts), Events, Book, Chapter,
                         LocalBooks (TXT/EPUB import), Backup (zip of everything, restore, WebDAV put/get)
settings/                SettingsActivity (+ res/xml/prefs.xml), RulesActivity, ScriptsActivity

assets/rules.json        built-in template rules      assets/adhosts.txt   bundled ad hosts
assets/cosmetic.js       injected element hider; "__STRICT__" is replaced with true/false
```

## 6. How the pieces behave (read before editing)

### Sniffing

- A page is a chapter when `chapterScore >= 60`, a book page when `detailScore >= 50`. Rule selectors override
  the matching detector; heuristics fill in whatever a rule omits.
- Learning: a heuristic chapter result with score >= 85, or a book result with score >= 65 and >= 10 chapters,
  is stored as a learned rule for the host.
- `r.confidence() >= 70` in the browser marks the host as a **novel host** (`RuleStore.addNovelHost`), which
  switches ad blocking to strict mode there.
- The browser feeds the **rendered** DOM (`outerHTML` through `evaluateJavascript`); background loading feeds
  HTTP-fetched HTML. Both must keep working, so do not rely on script-generated markup in the engine.
- Site habits the heuristics already cover (keep them working, each has a test): chapter addresses that end
  in a slash (`/book/20261/` is a page, `Urls.dirOf` and `guessTocUrl` treat it so); chapter lists collapsed
  behind a "show all" button, i.e. runs of 8+ hidden links (`Dom.clean` keeps those, other hidden nodes go);
  lists whose links wrap block elements (`<a><dd>`); a "latest chapters" block before the full list;
  scripted links whose `href` is a `javascript:` stub with the address in `data-href` / `data-url` /
  `data-link` or in an `onclick` location assignment (`HeuristicEngine.abs` reads all of them);
  anti-copy watermarks, i.e. one short Latin token glued to the end of many paragraphs of CJK text
  (`ContentCleaner.stripWatermark`); a heading that itself contains a title separator
  ("Prologue——Mirror - Book - Site": the book is what follows the whole heading); next/previous labels
  with a verb in front ("加载下一章", "阅读下一章", "继续下一章").
- A next/previous link that points at **another site** is never a chapter. The engine drops it and sets
  `ChapterContent.nextHijacked`, which tells the fetcher the real link is still unknown.
- False positives seen and guarded against: search result pages (content-share penalty), server directory
  listings, headings that link elsewhere. Add a `SnifferTest` case for every new one.

### Browser

- `BrowserActivity` is `singleTask` and handles `configChanges` itself; it is never recreated on rotation.
- A `Tab` has no WebView until shown (`view()`); restored and background tabs hold only `pendingUrl`.
  At most `MAX_LIVE` (6) WebViews exist; older tabs `sleep()` and reload on return.
  **Always null-check `tab.web`.**
- `Tab.url` is `""` on the start page (`isHome()`); the native start page covers the empty `webHolder`.
  `view-source:` tabs keep a synthetic URL that `onPageStarted` must not overwrite.
- Tabs report changes through `a.onTabChanged(tab)`; `refreshUi()` redraws all chrome from the current tab and
  is called very often (every progress tick), so keep it cheap. The bookmark star is cached in
  `starUrl`/`starOn`; set `starUrl = null` after changing bookmarks, `homeDirty = true` to rebuild the start page.
- Settings are plain `SharedPreferences`, re-applied to every tab in `onResume` via `Tab.applySettings()`.
  A new switch needs: a key in `prefs.xml` (or a menu toggle), the read in `applySettings`, strings in both
  languages.
- Session: `saveTabs()` runs in `onPause`/`onSaveInstanceState` (http(s), non-incognito tabs only); an
  `install -r` or a kill without pause loses the latest state, which is expected.
- JavaScript off (globally or per site) also disables `evaluateJavascript`, so sniffing, cosmetic filtering,
  night mode and user scripts do not run there.
- Incognito tabs: no history, not saved, no cache, but **cookies are shared** (WebView limitation). Do not
  describe them as isolated.
- Site icons: `Favicons` stores what WebView delivers in `onReceivedIcon` (not for incognito tabs) and never
  fetches anything itself, so it costs no request. Any new list of pages should call `Favicons.show(view, url,
  fallbackIcon, tint)` instead of drawing its own placeholder. Clearing history deletes the icons of sites that
  are not bookmarked.
- File access is on only so saved offline pages (`files/offline/*.mht`) can load; `Tab.isOfflinePage` gates
  both navigation and sub-requests. Keep that gate if you touch file handling.
- Pages never launch other apps without a user gesture **and** a confirmation dialog (`askOpenApp`).

- **Leaving the browser never destroys it.** `BrowserActivity` holds all tabs and their history in memory, so
  nothing may `finish()` it or start an activity below it with `FLAG_ACTIVITY_CLEAR_TOP`. The shelf button and
  Back-with-nowhere-to-go bring the shelf (or a reader that is still open) to the front with
  `FLAG_ACTIVITY_REORDER_TO_FRONT` (`leave()`); `ShelfActivity` answers Back with `moveTaskToBack` while
  `BrowserActivity.alive`. Only "clear data on exit" still finishes it. An address handed to the browser that
  is already open in a tab switches to that tab.

### Reader

- **Shown text is derived, cached text is raw.** `ReaderActivity.display()` = `FillerFilter.strip` (when on)
  then `TextRules.apply`; the result lives only in the `memory` cache. Offsets (progress, bookmarks, selection,
  speech) refer to the shown text. After changing a rule or the filter call `reprocess()`.
- Settings are static fields of `ReaderPrefs`; change one, then call `ReaderActivity.changed()` (save,
  re-typeset keeping the position, window flags). Themes are addressed by key ("b0".."b8" built in, "c..."
  the user's), never by index.
- Tap zones: `ReaderPrefs.zones`, 9 actions row by row. The zone editor refuses a grid without a menu cell.
- Selection lives in `PageView` (chapter offsets in the current slot); the activity only shows the action bar
  (`onSelection`). Any re-typesetting or page change drops it.
- Screen: `keepAwake()` sets `FLAG_KEEP_SCREEN_ON` and clears it N minutes after the last
  `onUserInteraction`. Break reminder: `restTick` counts foreground reading time (not while speech runs); the
  end of the break is stored in the pref `r_rest_until`, so leaving the reader does not skip it.
- Speech: `Speaker` feeds the engine one passage at a time and asks the activity for the next chapter
  (`onSpeakChapterEnd`); `deliver()` restarts it when that chapter's text arrives. `SpeakService` is only a
  foreground keep-alive so the process is not frozen with the screen off.
- Paper textures are computed (`Backgrounds.texture`), off the main thread, one bitmap cached. Do not add
  picture assets without a licence the owner has approved.
- Page view: a "page" is a spread of `cols` columns (2 when wide and `twoPage`); `ChapterLayout.pages` are
  columns, so every use goes through `pages(slot)`, `colLines`, `colOf`, `originY(k)`. Curl mode draws the
  turning page and the one under it into two bitmaps per turn (`prepareCurl`); when they cannot be allocated
  the turn falls back to the cover animation. Turning back is the same fold with the pages swapped.
- Actions: tap zones and gestures share one list (`ReaderPrefs.A_*`, stored as base-36 digits). The view does
  menu / previous / next itself and hands the rest to `ReaderActivity.onAction`.
- Highlights (`Notes`) store the passage text and are re-anchored to the shown text (`Notes.ranges`); the
  activity caches the ranges per chapter in `noteRanges` and must drop them when text or notes change. A
  passage that no longer occurs (a replacement rule, script conversion) is not drawn but stays in the list.
- Statistics (`Stats`) are fed from `restTick`: reader in front, no break, not listening, a touch within
  three minutes (or auto page running).
- `Ai` is plain `HttpURLConnection` on purpose (no SDK, see ground rules). Two formats: Claude Messages
  (`x-api-key`, `anthropic-version: 2023-06-01`, text blocks only, `stop_reason: "refusal"` reported) and
  OpenAI-style chat completions. No request without a key and a tap. Summaries are cached per chapter id in
  the prefs file `reader_ai`; "ask" sends only summaries and the part of the chapter already read.
- Online voice and WebDAV are requests to servers the user entered, made only on the user's action; they are
  off while their address is empty. Keys and passwords (`ai_key`, `webdav_pass`) are never written to a backup.
- Imported books (`Book.local()`, urlKey `local:<time>`): chapters have an empty url and the cached text
  is the only copy, so `Library.clearCache` skips them and nothing may fetch for them.
- `Backup.restore` swaps the database file and all preference files under the running app: the caller must
  end the process afterwards (`ShelfActivity.restored`).
- `Library.extendLinked` finding a chapter must be followed by moving there (`extendLinked()` in the activity
  does `jumpTo`); the reader asked for the next chapter, not for a longer list.

### Hijacked navigation (ads in place of the next chapter)

The answers are random per request, so the rule everywhere is: ask the same address again, do not conclude
"no chapter" or "end of book".

- `PageFetcher.fetch` makes up to 3 attempts (0.8 s, 1.6 s pauses) when the HTTP answer ended on another site
  (`Http.OffSite`, or a final URL on another registrable domain) or the chapter came back with `nextHijacked`.
  On the last attempt an off-site page is accepted if it really is the chapter/list asked for (moved domain).
- Reader, book without a chapter list ("linked"): reaching the end calls `Library.extendLinked`, which asks the
  last chapter's page again for its next link before saying "last chapter".
- Browser (`Tab.hijackedFrom`, novel hosts in strict mode only): a tapped same-site link whose navigation goes
  to another site is replaced by the link's own address; a server redirect from a same-site address to another
  site is re-requested up to 2 times, then allowed (a consistent redirect is the site's own: login, new domain).
- A linked book becomes a normal one only inside `Library.refreshToc`, after a list containing the current
  chapter has been merged. Nothing else may clear `linked` (doing so in `openFromDetail` froze books at the
  chapters read so far; `Library.repair` re-links those at start-up).

### Readers that continue at the bottom of the page (no "next" link)

Some readers have no next link at all: pulling up at the end of the page appends the next chapter and moves the
address bar on to it (chapter ids are not consecutive, so the address cannot be guessed). For a book without a
chapter list, `Library.loadChapter` passes `needNext` for the last known chapter; when the page then has no
next link, `WebFetcher.scrollForNext` first presses the page's own "next chapter" control (`CLICK_JS`: the
control exists but a script computes the address, e.g. `<a href="javascript:void(0)" data-type="next">`) and
watches the address for 3.5 s; then it scrolls the already loaded page to its end, fires scroll, wheel and
synthetic touch-drag events (`SCROLL_JS`; scroll events alone are not enough, such pages listen for the finger)
and returns the address the page moves to. Limits: the address must change and stay on the same site; it
costs the site one extra content request per chapter, which is unavoidable and therefore not behind a switch;
the hidden WebView takes up to 9 s when nothing happens (a real last chapter).

### Ad blocking

1. Network: bundled hosts + optional subscribed list (`files/adhosts_subscribed.txt`), suffix match;
   plus an ad-path pattern for third-party URLs.
2. Strict mode (novel hosts only): third-party `.js` and frame documents, gesture-less cross-site
   navigations, cross-site pop-ups. `CDN` allow-list keeps libraries and captchas working.
3. Cosmetic: `cosmetic.js`.

First-visit race: a scripted cross-site redirect that fires after page load but before the first sniff has
finished is **held** (`Tab.heldNavigation`) and dropped if the page turns out to be a novel page, carried out
otherwise. Keep this when changing sniff scheduling.

`AdBlocker.parseList` must only accept whole-domain rules; `example.com##.ad` and `||example.com/path` must
not block the domain (covered by `BrowserLogicTest`).

### Optional requests (all off by default)

| Key | What it gates | Without it |
|---|---|---|
| `auto_check_updates` | shelf re-reads every contents page on resume (2 h interval) | refresh button on the shelf |
| `check_at_end` | reader re-reads the contents page at the last page | Refresh chip in the contents drawer |
| `preload_chapters` | `PageView.ensureNeighbours` fetching the previous/next chapter | fetched on turning to it; cached neighbours still preload (`Host.mayPreload`) |
| `auto_toc` | contents fetch after `Library.openFromChapter` | book stays "linked"; Refresh chip in the drawer |
| `load_covers` | `Covers.fetch` network download | disk-cached covers, else generated cover |
| `search_suggest` | completions from the search engine | local history/bookmark suggestions |
| `restore_tabs` | reloading last session's tab on start | start page |

Still automatic because the book cannot be read without them: the contents fetch when a book has no chapters,
completing a paginated list after opening from a book page, the one contents fetch when a chapter of a known
book is not in its list, continuation pages of a split chapter, and the WebView retry in `PageFetcher` when
plain HTTP yields nothing usable. Whole-book caching is user-started (3 workers, 120 ms pause; not configurable).

### Data

- SQLite `novel.db`, **schema version 2**: `books`, `chapters`, `history` (with `visits`), `bookmarks`.
  A schema change needs a version bump and an `onUpgrade` step; `onCreate` must build the same result.
- Chapter text lives in files, not the database. Books without a contents page are "linked" books chained
  through next links (`Library.appendLinked`); they must not adopt an arbitrary list as contents.
- `Events` is the in-process bus the shelf, sheets and reader use to refresh.
- DB calls are synchronous. Small reads on the main thread are tolerated in existing code; anything that
  fetches or loops goes on `App.IO` and posts back with `App.MAIN`.

## 7. Conventions

- Comments explain constraints and reasons, not what the line does. Match the density of the file you edit.
- Threading pattern everywhere: `App.IO.execute(() -> { ...; App.MAIN.post(() -> { if (isDestroyed()) return; ... }); })`.
  Stale async results are discarded with sequence counters (`sniffSeq`, `suggestSeq`, `loadSeq`).
- Dialog callbacks from WebView (`SslErrorHandler`, `HttpAuthHandler`, `PermissionRequest`, geolocation,
  file chooser) must be answered on **every** path, including cancel and "not the current tab".
- Icons are 24dp Material vector drawables in `res/drawable/ic_*.xml`, tinted in code.
- Colours come from `res/values/styles.xml` (`brand`, `surface`, `page_bg`, `text_primary`, `text_secondary`,
  `field_bg`, `divider`, `badge`).
- Android strings: avoid apostrophes (they need escaping); a literal `%s` in a non-format string needs
  `formatted="false"`.

## 8. Before calling a change done

1. `:app:testDebugUnitTest` passes; new engine or parsing logic has a JVM test.
2. `:app:assembleDebug` is clean apart from the known deprecation noise.
3. Installed and exercised on the device against the mock site; for sniffing or ad-blocking changes also load
   `/book/88/1001.html` on **fresh data** (`pm clear`) and confirm the 2.5 s redirect is blocked and the
   reading-mode button appears.
4. `adb logcat -d -s AndroidRuntime:E` shows no crash from the app.
5. `README.md` (features, "Not included") and this file still describe reality.
6. Report what was verified on the device, what only by unit test, and what was not exercised.

## 9. Known gaps

- `Library.extendLinked` (end-of-book retry) and the moved-domain acceptance in `PageFetcher` have not been run
  on a device.
- A page in the browser that has already appended several chapters by scrolling is sniffed as one chapter
  (all appended text under the first title). Reading mode should be entered before scrolling on.
- Pull-to-continue readers that append chapters without changing the address are not followed.
- Single-address web-app readers (the whole book under one URL, chapters fetched as fragments by script and
  typeset in pages) are not supported: a chapter is identified by its address everywhere (`chapters.urlKey`).
  Supporting them means driving the page's own reader in the hidden WebView and storing chapters without an
  address; calling the site's fragment API directly would be a hard-coded site.
- Reading aloud with the **system** engine has not been run on a device (the test device has none; the
  passage loop, highlight, pause and chapter hand-over were run there through the online voice against a
  server that answers every request with a beep). Font import and background pictures (system file picker)
  were not exercised; screen-off timer only by reading the code.
- The AI assistant has never made a real request (no key at hand): only its settings dialog was opened.
  WebDAV upload/download, EPUB import on a device (unit test only), the word-list review cards, the night
  schedule, shake, double tap and the brightness edge are untested on a device.
- Simplified to traditional is one character to one character (first OpenCC candidate), so the usual
  one-to-many slips appear; no phrase table.
- Never exercised on a device: location / camera / microphone prompts, full-screen video, file upload,
  HTTP login dialog, save as PDF, add to home screen, bookmark import/export, volume-key paging in the reader.
- Built-in rule `wp-madara` is untested (no reachable site); the biquge
  rules were only run against the mock template.
- Not implemented: `blob:` downloads, QR scanning, page translation, bookmark folders, automatic sync,
  switching a book between source sites, an LLM-based sniffing engine, a voice per character, PDF/MOBI import.
- Author extraction fails on some real sites (5000yan, zggdwx).
