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
    private static final int REQ_EXPORT = 11, REQ_IMPORT = 12, REQ_BACKUP = 13, REQ_RESTORE = 14;
    private boolean backupWithText;
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
        // a start from the launcher: not a file handed over, not the browser's shelf button
        ReaderActivity.resumeOnStart(this, Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER), savedInstanceState != null);
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

        App.numpadEnter(search);
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
        findViewById(R.id.btnBrowser).setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);
        Events.add(this);
        if (savedInstanceState == null) openFile(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // brought back for a file, not started by the user
        leftByBack = false;
        openFile(intent);
    }

    /** A text file or EPUB handed over by a file manager. */
    private void openFile(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) importBook(intent.getData());
    }

    private void importBook(final Uri uri) {
        status.setText(R.string.sh_importing);
        status.setVisibility(View.VISIBLE);
        App.IO.execute(() -> {
            long id = -1;
            String error = null;
            try {
                id = com.rc.novelreader.data.LocalBooks.importFile(this, uri);
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final long book = id;
            final String err = error;
            App.MAIN.post(() -> {
                if (isDestroyed()) return;
                status.setVisibility(View.GONE);
                if (book < 0) App.toast(getString(R.string.sh_import_failed, err));
                else {
                    reload();
                    ReaderActivity.start(this, book, -1);
                }
            });
        });
    }

    private void pick(int request, String type) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), request);
        } catch (RuntimeException e) {
            App.toast(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ backup

    private void backupMenu() {
        String[] items = {getString(R.string.sh_backup_save), getString(R.string.sh_backup_save_text),
                getString(R.string.sh_backup_restore), getString(R.string.sh_dav_upload), getString(R.string.sh_dav_download),
                getString(R.string.sh_dav_settings)};
        new AlertDialog.Builder(this).setTitle(R.string.sh_backup).setItems(items, (d, w) -> {
            if (w == 0 || w == 1) {
                backupWithText = w == 1;
                try {
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, com.rc.novelreader.data.Backup.FILE_NAME), REQ_BACKUP);
                } catch (RuntimeException e) {
                    App.toast(String.valueOf(e.getMessage()));
                }
            } else if (w == 2) pick(REQ_RESTORE, "*/*");
            else if (w == 3) davUpload();
            else if (w == 4) confirmRestore(null);
            else davSettings();
        }).show();
    }

    private void busy(int text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void done(final String message) {
        App.MAIN.post(() -> {
            if (isDestroyed()) return;
            status.setVisibility(View.GONE);
            App.toast(message);
            reload();
        });
    }

    private void davSettings() {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(App.dp(22), App.dp(10), App.dp(22), 0);
        final EditText url = new EditText(this), user = new EditText(this), pass = new EditText(this);
        url.setHint(R.string.sh_dav_url);
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(App.prefs().getString("webdav_url", ""));
        user.setHint(R.string.sh_dav_user);
        user.setSingleLine(true);
        user.setText(App.prefs().getString("webdav_user", ""));
        pass.setHint(R.string.sh_dav_pass);
        pass.setSingleLine(true);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(App.prefs().getString("webdav_pass", ""));
        col.addView(url);
        col.addView(user);
        col.addView(pass);
        TextView note = new TextView(this);
        note.setText(R.string.sh_dav_note);
        note.setTextSize(12.5f);
        note.setPadding(0, App.dp(8), 0, 0);
        col.addView(note);
        new AlertDialog.Builder(this).setTitle(R.string.sh_dav_settings).setView(col).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> App.prefs().edit()
                        .putString("webdav_url", url.getText().toString().trim())
                        .putString("webdav_user", user.getText().toString().trim())
                        .putString("webdav_pass", pass.getText().toString()).apply()).show();
    }

    private boolean davReady() {
        if (!App.prefs().getString("webdav_url", "").isEmpty()) return true;
        davSettings();
        return false;
    }

    private void davUpload() {
        if (!davReady()) return;
        busy(R.string.sh_backup_working);
        App.IO.execute(() -> {
            java.io.File tmp = new java.io.File(getCacheDir(), "backup-upload.zip");
            try {
                try (OutputStream out = new java.io.FileOutputStream(tmp)) {
                    com.rc.novelreader.data.Backup.write(this, out, false);
                }
                com.rc.novelreader.data.Backup.upload(App.prefs().getString("webdav_url", ""), App.prefs().getString("webdav_user", ""),
                        App.prefs().getString("webdav_pass", ""), tmp);
                done(getString(R.string.sh_backup_done));
            } catch (Throwable t) {
                done(getString(R.string.sh_backup_failed, String.valueOf(t.getMessage())));
            } finally {
                tmp.delete();
            }
        });
    }

    /** @param file the backup to read, or null to fetch it from the WebDAV folder */
    private void confirmRestore(final Uri file) {
        if (file == null && !davReady()) return;
        new AlertDialog.Builder(this).setTitle(R.string.sh_backup_restore).setMessage(R.string.sh_restore_warning)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    busy(R.string.sh_backup_working);
                    App.IO.execute(() -> {
                        java.io.File tmp = new java.io.File(getCacheDir(), "backup-download.zip");
                        try {
                            if (file == null) {
                                com.rc.novelreader.data.Backup.download(App.prefs().getString("webdav_url", ""),
                                        App.prefs().getString("webdav_user", ""), App.prefs().getString("webdav_pass", ""), tmp);
                                try (java.io.InputStream in = new java.io.FileInputStream(tmp)) {
                                    com.rc.novelreader.data.Backup.restore(this, in);
                                }
                            } else {
                                try (java.io.InputStream in = getContentResolver().openInputStream(file)) {
                                    if (in == null) throw new java.io.IOException("cannot open file");
                                    com.rc.novelreader.data.Backup.restore(this, in);
                                }
                            }
                            App.MAIN.post(this::restored);
                        } catch (Throwable t) {
                            done(getString(R.string.sh_backup_failed, String.valueOf(t.getMessage())));
                        } finally {
                            tmp.delete();
                        }
                    });
                }).show();
    }

    /** Everything in memory is stale now: the app has to start again. */
    private void restored() {
        new AlertDialog.Builder(this).setMessage(R.string.sh_restore_done).setCancelable(false)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    finishAffinity();
                    Runtime.getRuntime().exit(0);
                }).show();
    }

    private void showStats() {
        long[] days = com.rc.novelreader.reader.Stats.lastDays(7);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.rt_stat_today)).append("  ").append(getString(R.string.rd_minutes, (int) (days[6] / 60000))).append('\n');
        long week = 0;
        for (long v : days) week += v;
        sb.append(getString(R.string.sh_stat_week)).append("  ").append(getString(R.string.rd_minutes, (int) (week / 60000))).append('\n');
        sb.append(getString(R.string.rt_stat_streak)).append("  ").append(getString(R.string.rt_days, com.rc.novelreader.reader.Stats.streak())).append('\n');
        long total = com.rc.novelreader.reader.Stats.totalMs() / 60000;
        sb.append(getString(R.string.rt_stat_total)).append("  ").append(getString(R.string.rt_hours_minutes, (int) (total / 60), (int) (total % 60)));
        int speed = com.rc.novelreader.reader.Stats.speed();
        if (speed > 0) sb.append('\n').append(getString(R.string.rt_stat_speed)).append("  ").append(getString(R.string.rt_per_minute, speed));
        new AlertDialog.Builder(this).setTitle(R.string.rt_stats).setMessage(sb.toString()).setPositiveButton(R.string.ok, null).show();
    }

    /**
     * While a browser with open tabs sits underneath, Back leaves the app without closing anything; finishing
     * the shelf would show that browser again instead.
     */
    @Override
    public void onBackPressed() {
        leftByBack = true;
        if (BrowserActivity.alive) moveTaskToBack(true);
        else super.onBackPressed();
    }

    /**
     * Back on the shelf leaves the app, but the shelf often lives on: we hide the task while the browser is
     * alive, and Android 12+ hides a launcher task instead of ending it. Coming back is then a start of the app
     * to the user (and gets "continue reading on start"), unlike a return after Home or from another app.
     */
    private boolean leftByBack;

    @Override
    protected void onRestart() {
        super.onRestart();
        if (!leftByBack) return;
        leftByBack = false;
        ReaderActivity.resumeOnStart(this, true, false);
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
        m.getMenu().add(0, 4, 2, R.string.sh_import);
        m.getMenu().add(0, 6, 2, R.string.rt_stats);
        m.getMenu().add(0, 5, 2, R.string.sh_backup);
        m.getMenu().add(0, 2, 3, R.string.settings);
        m.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100) {
                App.prefs().edit().putInt("shelf_sort", id - 100).apply();
                reload();
            } else if (id == 1) LibraryActivity.open(this, 1);
            else if (id == 3) LibraryActivity.open(this, 0);
            else if (id == 2) startActivity(new Intent(this, SettingsActivity.class));
            else if (id == 4) pick(REQ_IMPORT, "*/*");
            else if (id == 5) backupMenu();
            else if (id == 6) showStats();
            return true;
        });
        m.show();
    }

    private void showBookMenu(final Book b) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add(getString(R.string.book_info));
        acts.add(() -> BookSheet.show(this, b.id));
        labels.add(getString(b.pinned ? R.string.unpin : R.string.pin));
        acts.add(() -> {
            b.pinned = !b.pinned;
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("pinned", b.pinned ? 1 : 0);
            Db.updateBook(b.id, v);
            reload();
        });
        // an imported book is its own cache and has no page on the web
        if (!b.local()) {
            labels.add(getString(R.string.cache_all));
            acts.add(() -> {
                askNotifications();
                DownloadService.start(this, b.id, 0, -1);
            });
            labels.add(getString(R.string.clear_cache));
            acts.add(() -> App.IO.execute(() -> {
                Library.clearCache(b.id);
                App.toast(getString(R.string.cache_cleared));
            }));
        }
        labels.add(getString(R.string.export_txt));
        acts.add(() -> {
            exportBookId = b.id;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, b.title + ".txt");
            try {
                startActivityForResult(i, REQ_EXPORT);
            } catch (Exception e) {
                App.toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
            }
        });
        if (!b.local()) {
            labels.add(getString(R.string.open_source_page));
            acts.add(() -> BrowserActivity.open(this, b.detailUrl));
        }
        labels.add(getString(R.string.remove_book));
        acts.add(() -> new AlertDialog.Builder(this).setMessage(getString(R.string.remove_confirm, b.title))
                .setPositiveButton(R.string.ok, (dd, w) -> App.IO.execute(() -> {
                    Library.deleteBook(b.id);
                    App.toast(getString(R.string.removed));
                }))
                .setNegativeButton(R.string.cancel, null).show());
        new AlertDialog.Builder(this).setTitle(b.title).setItems(labels.toArray(new String[0]), (d, which) -> acts.get(which).run()).show();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            importBook(uri);
            return;
        }
        if (requestCode == REQ_RESTORE) {
            confirmRestore(uri);
            return;
        }
        if (requestCode == REQ_BACKUP) {
            busy(R.string.sh_backup_working);
            final boolean withText = backupWithText;
            App.IO.execute(() -> {
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new java.io.IOException("cannot open file");
                    com.rc.novelreader.data.Backup.write(this, out, withText);
                    done(getString(R.string.sh_backup_done));
                } catch (Throwable t) {
                    done(getString(R.string.sh_backup_failed, String.valueOf(t.getMessage())));
                }
            });
            return;
        }
        if (requestCode != REQ_EXPORT) return;
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
