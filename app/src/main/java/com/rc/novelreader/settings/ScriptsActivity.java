package com.rc.novelreader.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.browser.UserScripts;

/** Manages user scripts: JavaScript that runs on matching pages after they load. */
public class ScriptsActivity extends Activity {
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return UserScripts.all().size();
        }

        @Override
        public Object getItem(int position) {
            return UserScripts.all().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            final UserScripts.Script s = UserScripts.all().get(position);
            LinearLayout row = new LinearLayout(ScriptsActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(App.dp(16), App.dp(8), App.dp(8), App.dp(8));
            LinearLayout texts = new LinearLayout(ScriptsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(ScriptsActivity.this), t2 = new TextView(ScriptsActivity.this);
            t1.setText(s.name.isEmpty() ? getString(R.string.script_unnamed) : s.name);
            t1.setTextSize(15);
            t1.setTextColor(getColor(R.color.text_primary));
            t2.setText(s.match.replace('\n', ' '));
            t2.setTextSize(12);
            t2.setSingleLine(true);
            texts.addView(t1);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox on = new CheckBox(ScriptsActivity.this);
            on.setChecked(s.on);
            on.setFocusable(false);
            on.setOnCheckedChangeListener((b, checked) -> {
                s.on = checked;
                UserScripts.save();
            });
            row.addView(on);
            return row;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ListView list = new ListView(this);
        TextView empty = new TextView(this);
        empty.setText(R.string.scripts_empty);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(App.dp(32), 0, App.dp(32), 0);
        empty.setVisibility(View.GONE);
        addContentView(list, new ViewGroup.LayoutParams(-1, -1));
        addContentView(empty, new ViewGroup.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> edit(UserScripts.all().get(pos)));
    }

    private void edit(final UserScripts.Script existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(App.dp(20), App.dp(8), App.dp(20), 0);
        final EditText name = new EditText(this), match = new EditText(this), code = new EditText(this);
        name.setHint(R.string.script_name);
        name.setSingleLine(true);
        match.setHint(R.string.script_match);
        match.setTextSize(13);
        code.setHint(R.string.script_code);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(12.5f);
        code.setGravity(Gravity.TOP | Gravity.START);
        code.setMinLines(8);
        code.setHorizontallyScrolling(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (existing != null) {
            name.setText(existing.name);
            match.setText(existing.match);
            code.setText(existing.code);
        }
        box.addView(name);
        box.addView(match);
        box.addView(code);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.user_scripts).setView(scroll)
                .setPositiveButton(R.string.save, (d, w) -> {
                    UserScripts.Script s = existing == null ? new UserScripts.Script() : existing;
                    s.name = name.getText().toString().trim();
                    s.match = match.getText().toString().trim();
                    s.code = code.getText().toString();
                    if (s.code.trim().isEmpty()) return;
                    UserScripts.readHeader(s);
                    if (s.match.isEmpty()) s.match = "*";
                    if (existing == null) UserScripts.all().add(s);
                    UserScripts.save();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.cancel, null);
        if (existing != null) {
            b.setNeutralButton(R.string.remove, (d, w) -> {
                UserScripts.all().remove(existing);
                UserScripts.save();
                adapter.notifyDataSetChanged();
            });
        }
        b.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.new_script).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) finish();
        else if (item.getItemId() == 1) edit(null);
        return true;
    }
}
