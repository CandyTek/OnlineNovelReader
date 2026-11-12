package com.rc.novelreader.shelf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.browser.BrowserActivity;
import com.rc.novelreader.browser.LibraryActivity;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Events;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.reader.ReaderActivity;
import com.rc.novelreader.settings.SettingsActivity;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Home screen: the bookshelf, with a search box that leads into the browser. */
public class ShelfActivity extends Activity implements Events.Listener {
    private static final int REQ_EXPORT = 11;
    private static final long AUTO_CHECK_INTERVAL = 2 * 3600 * 1000L;
    private static final String[] ORDERS = {
            "pinned DESC, lastReadAt DESC", "pinned DESC, addedAt DESC",
            "pinned DESC, title COLLATE LOCALIZED", "pinned DESC, updatedAt DESC, lastReadAt DESC"};

    private final List<Book> books = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView status, empty;
    private EditText search;
    private boolean checking;
    private long exportBookId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shelf);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        GridView grid = findViewById(R.id.grid);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return books.size();
            }

            @Override
            public Object getItem(int position) {
                return books.get(position);
            }

            @Override
            public long getItemId(int position) {
                return books.get(position).id;
            }

            @Override
            public View getView(int position, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_book, parent, false);
                Book b = books.get(position);
                ((TextView) v.findViewById(R.id.title)).setText((b.pinned ? "📌 " : "") + b.title);
                String sub;
                if (b.chapterCount == 0) sub = getString(R.string.chapters_loading);
                else if (b.curIdx == 0 && b.curOffset == 0) sub = getString(R.string.unread) + " · " + getString(R.string.chapters_count, b.chapterCount);
                else sub = getString(R.string.chapter_of, Math.min(b.curIdx + 1, b.chapterCount), b.chapterCount);
                ((TextView) v.findViewById(R.id.sub)).setText(sub);
                TextView badge = v.findViewById(R.id.badge);
                badge.setVisibility(b.newCount > 0 ? View.VISIBLE : View.GONE);
                badge.setText(getString(R.string.new_badge, b.newCount));
                ProgressBar dl = v.findViewById(R.id.dl);
                DownloadService.Job job = DownloadService.job(b.id);
                dl.setVisibility(job != null ? View.VISIBLE : View.GONE);
                if (job != null) {
                    dl.setMax(Math.max(1, job.total));
                    dl.setProgress(job.done.get());
                }
                ImageView cover = v.findViewById(R.id.cover);
                if (!b.cover.equals(cover.getTag()) || b.cover.isEmpty()) Covers.load(cover, b);
                return v;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> ReaderActivity.start(this, id, -1));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showBookMenu(books.get(pos));
            return true;
        });

        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String q = search.getText().toString().trim();
                if (!q.isEmpty()) {
                    BrowserActivity.open(this, q);
                    search.setText("");
                    search.clearFocus();
                }
                return true;
            }
            return false;
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> checkUpdates(true));
        findViewById(R.id.btnBrowser).setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class)));
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);
        Events.add(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        long last = App.prefs().getLong("last_update_check", 0);
        if (App.prefs().getBoolean("auto_check_updates", false) && !books.isEmpty() && System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL) checkUpdates(false);
    }

    @Override
    protected void onDestroy() {
        Events.remove(this);
        super.onDestroy();
    }

    @Override
    public void onEvent(int type, long bookId) {
        if (type == Events.CACHE) adapter.notifyDataSetChanged();
        else reload();
    }

    private void reload() {
        int sort = Math.max(0, Math.min(ORDERS.length - 1, App.prefs().getInt("shelf_sort", 0)));
        books.clear();
        books.addAll(Db.books("inShelf=1", ORDERS[sort]));
        adapter.notifyDataSetChanged();
        empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void checkUpdates(final boolean manual) {
        if (checking) return;
        if (books.isEmpty()) {
            if (manual) App.toast(getString(R.string.no_updates));
            return;
        }
        checking = true;
        status.setText(R.string.checking_updates);
        status.setVisibility(View.VISIBLE);
        App.prefs().edit().putLong("last_update_check", System.currentTimeMillis()).apply();
        App.IO.execute(() -> {
            final int n = Library.checkUpdates();
            App.MAIN.post(() -> {
                checking = false;
                if (isDestroyed()) return;
                reload();
                if (n > 0 || manual) {
                    status.setText(n > 0 ? getString(R.string.updates_found, n) : getString(R.string.no_updates));
                    status.postDelayed(() -> status.setVisibility(View.GONE), 3000);
                } else status.setVisibility(View.GONE);
            });
        });
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        final int sort = App.prefs().getInt("shelf_sort", 0);
        int[] labels = {R.string.sort_recent, R.string.sort_added, R.string.sort_title, R.string.sort_update};
        android.view.SubMenu sm = m.getMenu().addSubMenu(R.string.sort);
        for (int i = 0; i < labels.length; i++) sm.add(1, 100 + i, i, labels[i]).setCheckable(true).setChecked(i == sort);
        sm.setGroupCheckable(1, true, true);
        m.getMenu().add(0, 3, 1, R.string.bookmarks);
        m.getMenu().add(0, 1, 1, R.string.history);
        m.getMenu().add(0, 2, 2, R.string.settings);
        m.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100) {
                App.prefs().edit().putInt("shelf_sort", id - 100).apply();
                reload();
            } else if (id == 1) LibraryActivity.open(this, 1);
            else if (id == 3) LibraryActivity.open(this, 0);
            else if (id == 2) startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });
        m.show();
    }

    private void showBookMenu(final Book b) {
        final String[] items = {
                getString(R.string.book_info),
                getString(b.pinned ? R.string.unpin : R.string.pin),
                getString(R.string.cache_all),
                getString(R.string.clear_cache),
                getString(R.string.export_txt),
                getString(R.string.open_source_page),
                getString(R.string.remove_book)};
        new AlertDialog.Builder(this).setTitle(b.title).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    BookSheet.show(this, b.id);
                    break;
                case 1:
                    b.pinned = !b.pinned;
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put("pinned", b.pinned ? 1 : 0);
                    Db.updateBook(b.id, v);
                    reload();
                    break;
                case 2:
                    askNotifications();
                    DownloadService.start(this, b.id, 0, -1);
                    break;
                case 3:
                    App.IO.execute(() -> {
                        Library.clearCache(b.id);
                        App.toast(getString(R.string.cache_cleared));
                    });
                    break;
                case 4:
                    exportBookId = b.id;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, b.title + ".txt");
                    try {
                        startActivityForResult(i, REQ_EXPORT);
                    } catch (Exception e) {
                        App.toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
                    }
                    break;
                case 5:
                    BrowserActivity.open(this, b.detailUrl);
                    break;
                case 6:
                    new AlertDialog.Builder(this).setMessage(getString(R.string.remove_confirm, b.title))
                            .setPositiveButton(R.string.ok, (dd, w) -> App.IO.execute(() -> {
                                Library.deleteBook(b.id);
                                App.toast(getString(R.string.removed));
                            }))
                            .setNegativeButton(R.string.cancel, null).show();
                    break;
                default:
                    break;
            }
        }).show();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final long id = exportBookId;
        App.IO.execute(() -> {
            try {
                Book b = Db.book(id);
                if (b == null) return;
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new java.io.IOException("cannot open file");
                int n = Library.exportTxt(b, out);
                App.toast(n > 0 ? getString(R.string.export_done, n) : getString(R.string.export_none));
            } catch (Exception e) {
                App.toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
            }
        });
    }
}
