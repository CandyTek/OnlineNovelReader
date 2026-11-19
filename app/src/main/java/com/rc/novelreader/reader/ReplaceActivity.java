package com.rc.novelreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;

import java.util.List;

/**
 * The list of the reader's text replacements ({@link TextRules}) on a screen of its own: there can be many, and
 * a dialog showed a handful at a time. The reader typesets its chapter again when it comes back to the front
 * ({@link #changed}).
 */
public class ReplaceActivity extends Activity {
    private static final String EXTRA_BOOK = "book";

    /** A rule was added, edited or removed since the reader last looked. */
    static boolean changed;

    private long bookId;
    private List<TextRules.Rule> rules;
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return rules.size();
        }

        @Override
        public Object getItem(int position) {
            return rules.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(ReplaceActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(App.dp(18), App.dp(11), App.dp(18), App.dp(11));
                TextView main = new TextView(ReplaceActivity.this);
                main.setTextSize(15.5f);
                main.setTextColor(getColor(R.color.text_primary));
                main.setMaxLines(3);
                main.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView sub = new TextView(ReplaceActivity.this);
                sub.setTextSize(12.5f);
                sub.setTextColor(getColor(R.color.text_secondary));
                sub.setPadding(0, App.dp(3), 0, 0);
                row.addView(main);
                row.addView(sub);
            }
            TextRules.Rule r = rules.get(position);
            ((TextView) row.getChildAt(0)).setText(shortened(r.find) + "  →  "
                    + (r.to.isEmpty() ? getString(R.string.rd_removed) : shortened(r.to)));
            String scope = getString(r.book == 0 ? R.string.rd_all_books : r.book == bookId ? R.string.rd_this_book : R.string.rd_other_book);
            ((TextView) row.getChildAt(1)).setText(r.regex ? scope + " · " + getString(R.string.rd_regex) : scope);
            return row;
        }
    };

    static void open(Context c, long bookId) {
        c.startActivity(new Intent(c, ReplaceActivity.class).putExtra(EXTRA_BOOK, bookId));
    }

    private static String shortened(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        bookId = getIntent().getLongExtra(EXTRA_BOOK, 0);
        rules = TextRules.all();
        FrameLayout root = new FrameLayout(this);
        ListView list = new ListView(this);
        TextView empty = new TextView(this);
        empty.setText(R.string.rd_replacements_empty);
        empty.setTextSize(14);
        empty.setTextColor(getColor(R.color.text_secondary));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(App.dp(32), App.dp(32), App.dp(32), App.dp(32));
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> edit(this, bookId, rules.get(pos), "", adapter::notifyDataSetChanged));
        setContentView(root);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.add).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) finish();
        else if (item.getItemId() == 1) edit(this, bookId, null, "", adapter::notifyDataSetChanged);
        return true;
    }

    /**
     * The dialog for one rule, also used by the reader for a rule made from selected text.
     * @param preset the text to find when the rule is new
     * @param done   called after the rule was saved or deleted
     */
    static void edit(final Activity a, final long bookId, final TextRules.Rule existing, String preset, final Runnable done) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(App.dp(22), App.dp(10), App.dp(22), App.dp(6));
        final EditText find = new EditText(a), to = new EditText(a);
        find.setHint(R.string.rd_find);
        find.setMaxLines(5);
        find.setText(existing == null ? preset : existing.find);
        to.setHint(R.string.rd_replace_with);
        to.setMaxLines(5);
        to.setText(existing == null ? "" : existing.to);
        final CheckBox regex = new CheckBox(a), only = new CheckBox(a);
        regex.setText(R.string.rd_regex);
        regex.setChecked(existing != null && existing.regex);
        only.setText(R.string.rd_this_book_only);
        only.setChecked(existing == null || existing.book != 0);
        col.addView(find);
        col.addView(to);
        col.addView(regex);
        col.addView(only);
        TextView note = new TextView(a);
        note.setText(R.string.rd_replace_note);
        note.setTextSize(12.5f);
        note.setTextColor(0xFF8C9792);
        note.setPadding(0, App.dp(6), 0, App.dp(8));
        col.addView(note);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(col);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(existing == null ? R.string.rd_replace : R.string.rd_edit_rule)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String f = find.getText().toString();
                    if (f.trim().isEmpty()) return;
                    if (regex.isChecked()) {
                        try {
                            java.util.regex.Pattern.compile(f);
                        } catch (Exception e) {
                            App.toast(a.getString(R.string.rd_regex_bad));
                            return;
                        }
                    }
                    TextRules.Rule r = existing == null ? new TextRules.Rule() : existing;
                    r.find = f;
                    r.to = to.getText().toString();
                    r.regex = regex.isChecked();
                    r.book = only.isChecked() ? (existing != null && existing.book != 0 ? existing.book : bookId) : 0;
                    if (existing == null) TextRules.all().add(r);
                    TextRules.save();
                    changed = true;
                    done.run();
                });
        if (existing != null) {
            b.setNeutralButton(R.string.rd_delete, (d, w) -> {
                TextRules.all().remove(existing);
                TextRules.save();
                changed = true;
                done.run();
            });
        }
        b.show();
    }
}
