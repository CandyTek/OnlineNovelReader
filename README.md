# Novel Sniffer (小说嗅探阅读器)

An Android browser that recognises novel pages on any site, strips the ads and clutter, and turns
them into a native reading experience with a bookshelf and whole-book caching.

- Package `com.rc.novelreader`, min SDK 23, target SDK 34, plain Java + framework views.
- Only dependency: [jsoup](https://jsoup.org) (HTML parsing for the sniffing engine).
- UI languages: English and Simplified Chinese.

## Build, install, test

```
gradlew.bat :app:assembleDebug            # app/build/outputs/apk/debug/NovelSniffer-debug.apk
gradlew.bat :app:testDebugUnitTest        # sniffing-engine tests (run on the JVM, no device needed)
adb install -r app/build/outputs/apk/debug/NovelSniffer-debug.apk
```

## How it works

### Module 1 - browser and ad blocking (`browser/`)

`BrowserActivity` is a tabbed WebView browser; each `Tab` owns a lazily created WebView, so restored and
background tabs cost nothing until shown (at most six stay live, older ones reload on return).

- **Tabs** - switcher sheet, background and incognito tabs, links that ask for a new window open as child tabs
  (Back returns to the parent), reopen closed tab, session restore, crash recovery of a single tab.
- **Address bar** - URL or search, suggestions from bookmarks, history and the search engine, paste-and-go,
  security icon with certificate details, bookmark star, per-page blocked counter.
- **Start page** - search box, bookmark tiles, most visited sites, shortcuts to the bookshelf and library.
- **Site icons** - the icon of every visited site is kept (one per host, no extra requests) and shown in
  bookmarks, history, start-page tiles, address suggestions and the tab list.
- **Library** (`LibraryActivity`) - bookmarks (edit, import/export as a standard bookmarks HTML file), history,
  downloads (system download manager: progress, open, delete) and saved offline pages, all searchable.
- **Page tools** - find in page, share, long-press menus for links and images, desktop site, night mode
  (colour inversion), no-images mode, text zoom, per-site JavaScript switch, view source, save as PDF, save
  offline (MHT), add to home screen, full-screen browsing, full-screen video, file upload.
- **Site permissions** - location, camera and microphone are asked per request; HTTP authentication, form
  resubmission and "open another app" prompts; pages can never launch other apps without a tap and a confirmation.
- **Settings** - search engine (incl. custom URL), user agent, cookies, clear data (also on exit), default browser,
  user scripts (`UserScripts`: Greasemonkey-style header, `@match` patterns, no `GM_*` API).

`AdBlocker` works in three layers:

1. **Network** - requests to hosts in `assets/adhosts.txt` (suffix match), to hosts of an optional subscribed
   list (hosts file, domain list or the domain rules of an Adblock list; Settings > Block list subscription)
   and ad-looking third-party URLs are answered with an empty response.
2. **Strict mode on novel sites** - once the sniffer has recognised a host as a novel site,
   third-party scripts and frames are blocked there too, script-initiated cross-site redirects are
   stopped, and pop-ups to other sites are dropped. A CDN/captcha allow-list keeps sites working.
   On a first visit a script redirect that fires before the sniffer has finished is held back and only
   carried out if the page turns out not to be a novel page.
3. **Cosmetic** - `assets/cosmetic.js` hides ad containers, floating boxes that show third-party
   media, and transparent full-page click-hijack layers; it re-runs when the DOM changes.

The feature set follows a survey of small open-source WebView browsers (Ninja, FOSS Browser and its forks,
Tuff Browser, Lightning, Yuzu). No code was taken from them: most are GPL or custom-licensed.

### Module 2 - dual-engine sniffer (`sniff/`, pure Java, unit-tested)

`Sniffer.analyze(url, html)` returns a `PageResult`: page type (chapter / book page / none), scores,
book metadata, chapter list, cleaned chapter text, next/previous/contents links.

- **Engine 1, rules (`RuleEngine`, `SiteRule`)** - CSS-selector rules, tried in the order
  user rules > learned rules > built-in template rules (`assets/rules.json`). A rule applies by host
  regex and/or by a structural *signature* selector, so one rule covers every clone of a template.
  Every field is optional.
- **Engine 2, heuristics (`HeuristicEngine`)** - three independent detectors:
  - *main text block*: text density, link density, paragraph structure, id/class hints, and the share
    of the page's text it holds (search results and portals spread text thin);
  - *navigation*: next / previous / contents links by label (text, `aria-label`, `rel`), inline script
    variables, breadcrumbs (HTML and schema.org JSON-LD), with paginated-chapter detection;
  - *chapter list*: the DOM container whose links best look like an ordered run of chapters
    (title patterns incl. Chinese numerals, URL shape, monotonic ids), de-duplicating "latest chapters"
    blocks, flipping newest-first lists, following list pagination and "all chapters" links.
  Rule selectors override the corresponding detector; the heuristics fill in everything a rule omits.
- **Learning** - when pure heuristics succeed with high confidence, the selectors they chose are stored
  as a learned rule for that host, which then also handles pages too short to score on their own.
- `ContentCleaner` removes watermarks, "remember this site" lines, nav crumbs and inline ads, and
  re-joins hard-wrapped lines of old fixed-width pages.

The browser feeds the *rendered* DOM to the sniffer; background loading uses plain HTTP first
(`net/Http`: shared user agent and cookies, GBK/GB18030 detection) and falls back to an off-screen
WebView (`net/WebFetcher`) for pages built by JavaScript or hidden behind interstitials.

### Module 3 - reader (`reader/`)

`PageView` typesets chapters itself and renders them as pages (cover / slide / instant turn) or as a
continuous vertical scroll, keeping the previous and next chapter loaded so turning never waits at a
chapter boundary. `ReaderActivity` adds the menus: contents drawer (cached chapters highlighted,
reversible), chapter slider, font size, line spacing, margins, five themes with a night toggle,
brightness, keep-screen-on, volume-key paging, reload chapter, open the original page.
Reading position (chapter + text offset) is restored exactly, also after changing the font.

Books whose site has no contents page are read in *linked mode*: chapters are chained through their
"next chapter" links as you read or cache.

### Module 4 - bookshelf (`shelf/`, `data/`)

`ShelfActivity` is the home screen: cover grid, search box into the browser, sort orders, pinning,
update checks (manual; automatic every 2 hours only if switched on) with "N new" badges, per-book cache /
clear cache / TXT export / remove. `DownloadService` caches chapters in a foreground service
(3 workers, stops itself if the site starts refusing requests). Chapter text is stored as files,
everything else in SQLite (`data/Db`).

## Custom site rules

Settings > Site rules takes a JSON array. Selector syntax is jsoup CSS, optionally followed by
`@attr` to read an attribute:

```json
[{
  "name": "example",
  "host": "(^|\\.)example\\.com$",
  "signature": "div#chapter-body",
  "content": "div#chapter-body",
  "chapterTitle": "h1.chapter-title",
  "next": "a.next-chapter", "prev": "a.prev-chapter", "tocLink": "a.back-to-book",
  "chapterList": "ul.chapters li a",
  "bookTitle": "h1.book-name", "author": "meta[name=author]@content",
  "cover": "img.cover@src", "intro": "div.synopsis",
  "purify": ["Visit example\\.com for more"]
}]
```

## Testing aids

- `tools/mock_site/gen.py <dir>` generates a deliberately messy fake novel site (GBK template site with
  ad overlays and paginated chapters, paginated chapter list, English unnumbered list, JavaScript-rendered
  book, book without a contents page, a portal home page that must not be detected). Serve it with
  `python -m http.server 8765 --directory <dir>` and open it from the device.
- Debug builds add "Mark verified", "Mark failed" and "Test lists" at the end of the browser menu: a local record
  of which sites were tested by hand, with a badge in the top right corner of pages on a marked site.
- In debuggable builds the browser's "Sniff this page" button (magnifier) saves the page DOM to
  `files/sniff/`. Pull those files into a folder and run the unit tests with `SNIFF_DIR=<folder>`:
  `RealPagesTest` writes `report.txt` there with what the engine made of each page.

## Hijacked navigation

Some sites answer "next chapter" with an advertiser at random: a redirect to another domain, or the ad in the
link itself. Such answers are never taken as "no more chapters". The reader asks the same address again (up to
3 times), and at the end of a book that has no chapter list it asks the last page again for its next link. In
the browser, on recognised novel sites, a tapped link that lands on another site is re-requested.

A "next chapter" button without an address (a script decides where it goes) is pressed in the background page
and the address it leads to is taken. Readers without any "next" link, which load the next chapter when you
pull up at the bottom of the page, are followed too: the background page is scrolled to its end with a simulated finger drag and the address it moves
to becomes the next chapter.

No site is special-cased: built-in rules match page templates by structure only, never by host name.

## Request budget

Novel sites often count requests per IP, so by default the app only requests what you ask for: the page you
browse, the chapter you turn to, a contents page you refresh. Everything else is a switch under
Settings > "Optional requests to novel sites", off by default: automatic shelf update checks, the check for
new chapters at the last page, preloading neighbouring chapters, fetching the contents page when a book is
opened from a chapter page, and cover downloads. Search suggestions and restoring tabs on start are off by
default too.

## Not included

Text-to-speech, page-curl animation, switching a book between source sites, and an LLM-based engine
(the second engine is algorithmic). In the browser: incognito tabs keep out of history and the tab session but
share cookies with normal tabs (a WebView limitation), `blob:` downloads, QR scanning, page translation,
bookmark folders and sync are not implemented.
