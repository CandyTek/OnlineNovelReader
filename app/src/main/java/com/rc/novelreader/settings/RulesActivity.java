package com.rc.novelreader.settings;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.data.RuleStore;

/** Editor for user-defined site rules (JSON) and a read-only view of what the engine has learned. */
public class RulesActivity extends Activity {
    private static final String EXAMPLE = "[\n  {\n    \"name\": \"example\",\n    \"host\": \"(^|\\\\.)example\\\\.com$\",\n"
            + "    \"content\": \"div#chapter-body\",\n    \"chapterTitle\": \"h1.chapter-title\",\n"
            + "    \"next\": \"a.next-chapter\",\n    \"prev\": \"a.prev-chapter\",\n    \"tocLink\": \"a.back-to-book\",\n"
            + "    \"chapterList\": \"ul.chapters li a\",\n    \"bookTitle\": \"h1.book-name\",\n"
            + "    \"author\": \"meta[name=author]@content\",\n    \"cover\": \"img.cover@src\",\n"
            + "    \"intro\": \"div.synopsis\",\n    \"purify\": [\"Visit example\\\\.com for more\"]\n  }\n]";

    private EditText editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        int pad = App.dp(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText(R.string.rules_help);
        help.setTextSize(13);
        box.addView(help);

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(12.5f);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(10);
        editor.setHorizontallyScrolling(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setText(RuleStore.userJson());
        box.addView(editor, new LinearLayout.LayoutParams(-1, -2));

        TextView learnedTitle = new TextView(this);
        learnedTitle.setText(R.string.learned_rules);
        learnedTitle.setTypeface(Typeface.DEFAULT_BOLD);
        learnedTitle.setPadding(0, pad, 0, App.dp(6));
        box.addView(learnedTitle);

        TextView learned = new TextView(this);
        learned.setTypeface(Typeface.MONOSPACE);
        learned.setTextSize(11.5f);
        learned.setTextIsSelectable(true);
        String l = RuleStore.learnedJson();
        learned.setText(l.isEmpty() ? getString(R.string.none) : l);
        box.addView(learned);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 2, 0, R.string.rules_example).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 1, 1, R.string.save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) finish();
        else if (item.getItemId() == 2) {
            if (editor.getText().toString().trim().isEmpty()) editor.setText(EXAMPLE);
            else editor.append("\n" + EXAMPLE);
        } else if (item.getItemId() == 1) {
            try {
                int n = RuleStore.saveUser(editor.getText().toString());
                App.toast(getString(R.string.rules_saved, n));
                finish();
            } catch (IllegalArgumentException e) {
                new android.app.AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton(R.string.ok, null).show();
            }
        }
        return true;
    }
}
