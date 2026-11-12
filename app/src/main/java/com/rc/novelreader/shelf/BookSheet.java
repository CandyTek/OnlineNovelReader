package com.rc.novelreader.shelf;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.Book;
import com.rc.novelreader.data.Db;
import com.rc.novelreader.data.Events;
import com.rc.novelreader.data.Library;
import com.rc.novelreader.reader.ReaderActivity;
import com.rc.novelreader.util.Urls;

/** Bottom sheet with a book's details and the main actions: read, add to shelf, cache. */
public final class BookSheet {
    private BookSheet() {}

    public static void show(final Activity a, final long bookId) {
        final Book first = Db.book(bookId);
        if (first == null || a.isFinishing()) return;
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_book);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
        }
        final TextView title = d.findViewById(R.id.title), author = d.findViewById(R.id.author),
                chapters = d.findViewById(R.id.chapters), source = d.findViewById(R.id.source),
                intro = d.findViewById(R.id.intro), btnShelf = d.findViewById(R.id.btnShelf),
                btnRead = d.findViewById(R.id.btnRead), btnCache = d.findViewById(R.id.btnCache);
        final ImageView cover = d.findViewById(R.id.cover);

        final Runnable bind = () -> {
            Book b = Db.book(bookId);
            if (b == null) return;
            title.setText(b.title);
            author.setText(b.author.isEmpty() ? a.getString(R.string.unknown_author) : a.getString(R.string.author_label, b.author));
            int cached = Db.cachedCount(bookId);
            chapters.setText(b.chapterCount == 0 ? a.getString(R.string.chapters_loading)
                    : a.getString(R.string.cached_info, b.chapterCount, cached));
            source.setText(a.getString(R.string.source_label, Urls.host(b.detailUrl)));
            intro.setText(b.intro);
            intro.setVisibility(b.intro.isEmpty() ? View.GONE : View.VISIBLE);
            btnShelf.setText(b.inShelf ? R.string.in_shelf : R.string.add_to_shelf);
            btnShelf.setAlpha(b.inShelf ? 0.5f : 1f);
            boolean started = b.curIdx > 0 || b.curOffset > 0;
            btnRead.setText(started ? R.string.continue_reading : R.string.start_reading);
            Covers.load(cover, b);
        };
        bind.run();

        final Events.Listener listener = (type, id) -> {
            if (id == bookId || id == 0) bind.run();
        };
        Events.add(listener);
        d.setOnDismissListener(x -> Events.remove(listener));

        btnShelf.setOnClickListener(v -> {
            Book b = Db.book(bookId);
            if (b == null || b.inShelf) return;
            Library.setShelf(bookId, true);
            App.toast(a.getString(R.string.added_to_shelf));
            bind.run();
        });
        btnRead.setOnClickListener(v -> {
            d.dismiss();
            ReaderActivity.start(a, bookId, -1);
        });
        btnCache.setOnClickListener(v -> {
            Book b = Db.book(bookId);
            if (b == null) return;
            if (!b.inShelf) Library.setShelf(bookId, true);
            DownloadService.start(a, bookId, 0, -1);
            bind.run();
        });
        d.show();
    }
}
