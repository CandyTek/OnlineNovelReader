package com.rc.novelreader.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SearchView;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Db;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bookmarks, history, downloads and saved offline pages in one screen. */
public class LibraryActivity extends Activity {
    private static final int BOOKMARKS = 0, HISTORY = 1, DOWNLOADS = 2, OFFLINE = 3;
    private static final int REQ_EXPORT = 21, REQ_IMPORT = 22;

    /** What a row shows and carries: key is a URL, a download id or a file path. */
    private static final class Row {
        String key, title, sub, extra;
        boolean done;
    }

    private final List<Row> rows = new ArrayList<>();
    private final TextView[] tabViews = new TextView[4];
    private TextView empty;
    private int tab, loadSeq;
    private String filter = "";
    private boolean resumed;

    public static void open(Context c, int tab) {
        c.startActivity(new Intent(c, LibraryActivity.class).putExtra("tab", tab));
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout row = new LinearLayout(LibraryActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(App.dp(60));
                row.setPadding(App.dp(16), 0, App.dp(16), 0);
                ImageView icon = new ImageView(LibraryActivity.this);
                icon.setId(android.R.id.icon);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(App.dp(24), App.dp(24));
                ip.setMarginEnd(App.dp(16));
                row.addView(icon, ip);
                LinearLayout texts = new LinearLayout(LibraryActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView a = new TextView(LibraryActivity.this), b = new TextView(LibraryActivity.this);
                a.setId(android.R.id.text1);
                b.setId(android.R.id.text2);
                a.setTextSize(15.5f);
                a.setTextColor(getColor(R.color.text_primary));
                b.setTextSize(12.5f);
                b.setTextColor(getColor(R.color.text_secondary));
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                b.setSingleLine(true);
                b.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                texts.addView(a);
                texts.addView(b);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                v = row;
            }
            Row r = rows.get(position);
            TextView t1 = v.findViewById(android.R.id.text1), t2 = v.findViewById(android.R.id.text2);
            ImageView icon = v.findViewById(android.R.id.icon);
            int grey = getColor(R.color.text_secondary);
            if (tab == BOOKMARKS || tab == HISTORY) Favicons.show(icon, r.key, R.drawable.ic_globe, grey);
            else Favicons.show(icon, null, tab == DOWNLOADS ? R.drawable.ic_download : R.drawable.ic_save, grey);
            t1.setText(r.title);
            t2.setText(r.sub);
            return v;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        tab = savedInstanceState != null ? savedInstanceState.getInt("tab") : getIntent().getIntExtra("tab", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout strip = new LinearLayout(this);
        strip.setBackgroundColor(getColor(R.color.surface));
        strip.setElevation(App.dp(2));
        int[] names = {R.string.bookmarks, R.string.history, R.string.downloads, R.string.offline_pages};
        for (int i = 0; i < 4; i++) {
            final int which = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(13.5f);
            t.setSingleLine(true);
            t.setOnClickListener(v -> select(which));
            strip.addView(t, new LinearLayout.LayoutParams(0, App.dp(46), 1));
            tabViews[i] = t;
        }
        root.addView(strip);
        ListView list = new ListView(this);
        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(getColor(R.color.text_secondary));
        empty.setVisibility(View.GONE);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> click(rows.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            longClick(v, rows.get(pos));
            return true;
        });
        setContentView(root);
        // the number comes from an intent: one that names no tab must not take the screen down
        select(tab < 0 || tab > 3 ? 0 : tab);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load();
        tick();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    /** Download progress is polled while that tab is in view. */
    private void tick() {
        App.MAIN.postDelayed(() -> {
            if (!resumed) return;
            if (tab == DOWNLOADS) load();
            tick();
        }, 1500);
    }

    private void select(int which) {
        tab = which;
        for (int i = 0; i < 4; i++) {
            tabViews[i].setTextColor(getColor(i == which ? R.color.brand : R.color.text_secondary));
            tabViews[i].setTypeface(i == which ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        int[] titles = {R.string.bookmarks, R.string.history, R.string.downloads, R.string.offline_pages};
        setTitle(titles[which]);
        int[] empties = {R.string.bookmarks_empty, R.string.history_empty, R.string.downloads_empty, R.string.offline_empty};
        empty.setText(empties[which]);
        invalidateOptionsMenu();
        rows.clear();
        adapter.notifyDataSetChanged();
        load();
    }

    private void load() {
        final int seq = ++loadSeq, which = tab;
        final String f = filter.toLowerCase(Locale.ROOT);
        App.IO.execute(() -> {
            final List<Row> out = new ArrayList<>();
            try {
                if (which == BOOKMARKS || which == HISTORY) {
                    for (String[] r : which == BOOKMARKS ? Db.bookmarks() : Db.history()) {
                        Row row = new Row();
                        row.key = r[0];
                        row.title = r[1] == null || r[1].isEmpty() ? r[0] : r[1];
                        row.sub = which == BOOKMARKS ? r[0]
                                : DateUtils.getRelativeTimeSpanString(Long.parseLong(r[2])) + " · " + r[0];
                        out.add(row);
                    }
                } else if (which == DOWNLOADS) loadDownloads(out);
                else {
                    File[] files = Tab.offlineDir().listFiles();
                    if (files != null) {
                        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        for (File file : files) {
                            Row row = new Row();
                            row.key = file.getAbsolutePath();
                            row.title = file.getName().replaceFirst(" \\d+\\.mht$", "");
                            row.sub = DateUtils.formatDateTime(this, file.lastModified(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)
                                    + " · " + Formatter.formatFileSize(this, file.length());
                            out.add(row);
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("Library", e);
            }
            if (!f.isEmpty()) {
                for (int i = out.size() - 1; i >= 0; i--) {
                    Row r = out.get(i);
                    if (!r.title.toLowerCase(Locale.ROOT).contains(f) && !r.sub.toLowerCase(Locale.ROOT).contains(f)) out.remove(i);
                }
            }
            App.MAIN.post(() -> {
                if (seq != loadSeq || isDestroyed()) return;
                rows.clear();
                rows.addAll(out);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void loadDownloads(List<Row> out) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        try (Cursor c = dm.query(new DownloadManager.Query())) {
            if (c == null) return;
            int id = c.getColumnIndex(DownloadManager.COLUMN_ID), title = c.getColumnIndex(DownloadManager.COLUMN_TITLE),
                    status = c.getColumnIndex(DownloadManager.COLUMN_STATUS), sofar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    total = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), time = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP),
                    uri = c.getColumnIndex(DownloadManager.COLUMN_URI);
            while (c.moveToNext()) {
                Row r = new Row();
                r.key = String.valueOf(c.getLong(id));
                r.title = c.getString(title);
                r.extra = c.getString(uri);
                if (r.title == null || r.title.isEmpty()) r.title = String.valueOf(r.extra);
                long done = c.getLong(sofar), all = c.getLong(total);
                String size = all > 0 ? Formatter.formatFileSize(this, all) : "";
                switch (c.getInt(status)) {
                    case DownloadManager.STATUS_SUCCESSFUL:
                        r.done = true;
                        r.sub = size + " · " + DateUtils.getRelativeTimeSpanString(c.getLong(time));
                        break;
                    case DownloadManager.STATUS_FAILED:
                        r.sub = getString(R.string.dl_failed);
                        break;
                    case DownloadManager.STATUS_PAUSED:
                        r.sub = getString(R.string.dl_paused) + (all > 0 ? " · " + (done * 100 / all) + "%" : "");
                        break;
                    case DownloadManager.STATUS_PENDING:
                        r.sub = getString(R.string.dl_waiting);
                        break;
                    default:
                        r.sub = (all > 0 ? (done * 100 / all) + "% · " : "") + Formatter.formatFileSize(this, done)
                                + (all > 0 ? " / " + size : "");
                }
                out.add(r);
            }
        }
        Collections.reverse(out); // newest first
    }

    private void click(Row r) {
        if (tab == BOOKMARKS || tab == HISTORY) {
            BrowserActivity.open(this, r.key);
            finish();
        } else if (tab == OFFLINE) {
            BrowserActivity.open(this, Uri.fromFile(new File(r.key)).toString());
            finish();
        } else if (r.done) openDownload(Long.parseLong(r.key));
    }

    private void openDownload(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        try {
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) throw new IllegalStateException();
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            App.toast(getString(R.string.no_app_found));
        }
    }

    private void longClick(View anchor, final Row r) {
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        if (tab == BOOKMARKS) m.getMenu().add(0, 1, 0, R.string.edit);
        if (tab == HISTORY) m.getMenu().add(0, 2, 0, R.string.add_bookmark);
        if (tab != OFFLINE && r.extra != null || tab == BOOKMARKS || tab == HISTORY) m.getMenu().add(0, 3, 0, R.string.copy_link);
        if (tab == BOOKMARKS || tab == HISTORY) m.getMenu().add(0, 4, 0, R.string.share);
        m.getMenu().add(0, 5, 0, R.string.remove);
        m.setOnMenuItemClickListener(item -> {
            String link = tab == DOWNLOADS ? r.extra : r.key;
            switch (item.getItemId()) {
                case 1:
                    editBookmark(r.key, r.title);
                    break;
                case 2:
                    Db.addBookmark(r.key, r.title);
                    App.toast(getString(R.string.bookmark_added));
                    break;
                case 3:
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("url", link));
                    App.toast(getString(R.string.link_copied));
                    break;
                case 4:
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, link), null));
                    break;
                default:
                    if (tab == BOOKMARKS) Db.removeBookmark(r.key);
                    else if (tab == HISTORY) Db.deleteHistory(r.key);
                    else if (tab == DOWNLOADS) ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).remove(Long.parseLong(r.key));
                    else new File(r.key).delete();
                    load();
            }
            return true;
        });
        m.show();
    }

    /** Also used for a new bookmark when url is null. */
    private void editBookmark(final String url, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(App.dp(20), App.dp(8), App.dp(20), 0);
        final EditText name = new EditText(this), address = new EditText(this);
        name.setHint(R.string.title_hint);
        name.setSingleLine(true);
        name.setText(title);
        address.setHint("https://");
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(url);
        box.addView(name);
        box.addView(address);
        new AlertDialog.Builder(this).setTitle(url == null ? R.string.add_bookmark : R.string.edit).setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String u = address.getText().toString().trim(), t = name.getText().toString().trim();
                    if (u.isEmpty()) return;
                    if (!u.matches("(?i)^[a-z]+://.*")) u = "http://" + u;
                    if (url == null) Db.addBookmark(u, t);
                    else Db.updateBookmark(url, u, t);
                    load();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        SearchView search = new SearchView(getActionBar() == null ? this : getActionBar().getThemedContext());
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String text) {
                filter = text == null ? "" : text.trim();
                load();
                return true;
            }
        });
        menu.add(0, 1, 0, R.string.search).setActionView(search).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (tab == BOOKMARKS) {
            menu.add(0, 2, 1, R.string.add_bookmark);
            menu.add(0, 3, 2, R.string.export_bookmarks);
            menu.add(0, 4, 3, R.string.import_bookmarks);
        } else if (tab == HISTORY) menu.add(0, 5, 1, R.string.clear_history);
        else if (tab == DOWNLOADS) menu.add(0, 6, 1, R.string.system_downloads);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case 2:
                editBookmark(null, "");
                break;
            case 3:
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/html").putExtra(Intent.EXTRA_TITLE, "bookmarks.html"), REQ_EXPORT);
                break;
            case 4:
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"), REQ_IMPORT);
                break;
            case 5:
                Db.clearHistory();
                Favicons.clearUnbookmarked();
                load();
                break;
            case 6:
                Downloads.openSystemList(this);
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    // ------------------------------------------------------------------ bookmark import / export

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean export = requestCode == REQ_EXPORT;
        App.IO.execute(() -> {
            int n = 0;
            try {
                if (export) {
                    // the Netscape bookmark file format every browser can read
                    StringBuilder sb = new StringBuilder("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n"
                            + "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n"
                            + "<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n");
                    for (String[] r : Db.bookmarks()) {
                        sb.append("    <DT><A HREF=\"").append(TextUtils.htmlEncode(r[0])).append("\" ADD_DATE=\"")
                                .append(Long.parseLong(r[2]) / 1000).append("\">").append(TextUtils.htmlEncode(r[1])).append("</A>\n");
                        n++;
                    }
                    sb.append("</DL><p>\n");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(sb.toString().getBytes("UTF-8"));
                    }
                } else {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        List<Element> links = Jsoup.parse(in, null, "").select("a[href]");
                        Collections.reverse(links); // the first entry ends up newest, keeping the file's order
                        for (Element a : links) {
                            String href = a.attr("href");
                            if (!href.startsWith("http")) continue;
                            Db.addBookmark(href, a.text());
                            n++;
                        }
                    }
                }
                App.toast(getString(export ? R.string.bookmarks_exported : R.string.bookmarks_imported, n));
            } catch (Exception e) {
                App.toast(getString(R.string.save_failed));
            }
            App.MAIN.post(this::load);
        });
    }
}
