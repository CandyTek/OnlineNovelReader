package com.rc.novelreader.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.browser.AdBlocker;
import com.rc.novelreader.browser.BrowserActivity;
import com.rc.novelreader.data.RuleStore;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public class SettingsActivity extends Activity {
    public static final String DEFAULT_AD_LIST = "https://anti-ad.net/domains.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction().replace(android.R.id.content, new Prefs()).commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) finish();
        return true;
    }

    @Override
    protected void onPause() {
        AdBlocker.reload();
        super.onPause();
    }

    public static class Prefs extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);
            findPreference("rules").setOnPreferenceClickListener(p -> {
                startActivity(new Intent(getActivity(), RulesActivity.class));
                return true;
            });
            findPreference("user_scripts").setOnPreferenceClickListener(p -> {
                startActivity(new Intent(getActivity(), ScriptsActivity.class));
                return true;
            });
            findPreference("clear_learned").setOnPreferenceClickListener(p -> {
                RuleStore.clearLearned();
                bind();
                App.toast(getString(R.string.cleared));
                return true;
            });
            findPreference("clear_browsing").setOnPreferenceClickListener(p -> {
                BrowserActivity.clearDataDialog(getActivity(), null);
                return true;
            });
            findPreference("long_press").setOnPreferenceClickListener(p -> {
                com.rc.novelreader.browser.BarActions.configure(getActivity());
                return true;
            });
            findPreference("default_browser").setOnPreferenceClickListener(p -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
                } catch (Exception e) {
                    App.toast(getString(R.string.no_app_found));
                }
                return true;
            });
            findPreference("ad_list").setOnPreferenceClickListener(p -> {
                adListDialog();
                return true;
            });
            findPreference("ad_sites_off").setOnPreferenceClickListener(p -> {
                sitesOffDialog();
                return true;
            });
            findPreference("about").setOnPreferenceClickListener(p -> {
                String version = "";
                try {
                    version = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                new AlertDialog.Builder(getActivity()).setMessage(getString(R.string.about_text, version))
                        .setPositiveButton(R.string.ok, null).show();
                return true;
            });
        }

        @Override
        public void onResume() {
            super.onResume();
            bind();
        }

        private void bind() {
            int[] c = RuleStore.counts();
            findPreference("rules").setSummary(getString(R.string.pref_rules_sum, c[0], c[1], c[2]));
            int[] a = AdBlocker.counts();
            findPreference("ad_list").setSummary(getString(R.string.pref_ad_list_sum, a[0], a[1]));
            findPreference("ad_sites_off").setSummary(String.valueOf(AdBlocker.sitesOff().size()));
            String ua = App.prefs().getString("ua_custom", "");
            findPreference("ua_custom").setSummary(ua.isEmpty() ? getString(R.string.none) : ua);
        }

        /** Subscribes to a hosts / domain block list, downloaded on demand. */
        private void adListDialog() {
            final Activity a = getActivity();
            final EditText url = new EditText(a);
            url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            url.setText(App.prefs().getString("ad_list_url", DEFAULT_AD_LIST));
            FrameLayout box = new FrameLayout(a);
            box.setPadding(App.dp(20), App.dp(8), App.dp(20), 0);
            box.addView(url);
            new AlertDialog.Builder(a).setTitle(R.string.pref_ad_list).setMessage(R.string.ad_list_help).setView(box)
                    .setPositiveButton(R.string.update, (d, w) -> {
                        final String u = url.getText().toString().trim();
                        if (!u.startsWith("http")) return;
                        App.prefs().edit().putString("ad_list_url", u).apply();
                        App.toast(getString(R.string.updating));
                        App.IO.execute(() -> {
                            try {
                                int n = AdBlocker.updateList(u);
                                App.toast(a.getString(R.string.ad_list_updated, n));
                            } catch (Exception e) {
                                App.toast(a.getString(R.string.download_failed, String.valueOf(e.getMessage())));
                            }
                            App.MAIN.post(() -> {
                                if (isAdded()) bind();
                            });
                        });
                    })
                    .setNeutralButton(R.string.remove, (d, w) -> {
                        AdBlocker.removeList();
                        bind();
                    })
                    .setNegativeButton(R.string.cancel, null).show();
        }

        private void sitesOffDialog() {
            final List<String> hosts = new ArrayList<>(AdBlocker.sitesOff());
            if (hosts.isEmpty()) {
                App.toast(getString(R.string.none));
                return;
            }
            final boolean[] keep = new boolean[hosts.size()];
            java.util.Arrays.fill(keep, true);
            new AlertDialog.Builder(getActivity()).setTitle(R.string.pref_ad_sites_off)
                    .setMultiChoiceItems(hosts.toArray(new CharSequence[0]), keep, (d, which, on) -> keep[which] = on)
                    .setPositiveButton(R.string.ok, (d, w) -> {
                        for (int i = 0; i < keep.length; i++) {
                            if (!keep[i]) AdBlocker.setSiteOff(hosts.get(i), false);
                        }
                        bind();
                    })
                    .setNegativeButton(R.string.cancel, null).show();
        }
    }
}
