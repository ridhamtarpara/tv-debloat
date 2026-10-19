package com.calm.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Every launchable app in a quiet grid. OK opens, long-press adds or removes it from the dock. */
public class AllAppsActivity extends Activity {
    private List<Apps.App> apps;
    private View lifted;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        apps = Apps.all(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1013);
        root.setPadding(dp(48), dp(28), dp(48), 0);
        root.setClipChildren(false);

        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        root.addView(title);

        final GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(28));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(16), dp(24), dp(16), dp(32));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                Apps.App a = apps.get(i);
                LinearLayout cell = new LinearLayout(AllAppsActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setClipChildren(false);
                View card = Apps.card(AllAppsActivity.this, a, dp(140), dp(79));
                card.setFocusable(false); // the GridView owns focus; we lift the selected cell
                cell.addView(card);
                TextView name = new TextView(AllAppsActivity.this);
                name.setText(a.label);
                name.setTextColor(0xCCFFFFFF);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                name.setSingleLine(true);
                name.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(140), -2);
                lp.topMargin = dp(10);
                cell.addView(name, lp);
                return cell;
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (lifted != null) Apps.setLifted(AllAppsActivity.this, lifted, false);
                lifted = ((ViewGroup) v).getChildAt(0);
                Apps.setLifted(AllAppsActivity.this, lifted, true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
                if (lifted != null) Apps.setLifted(AllAppsActivity.this, lifted, false);
                lifted = null;
            }
        });
        grid.setOnItemClickListener((p, v, pos, id) -> {
            try {
                startActivity(apps.get(pos).launch);
            } catch (Exception ignored) {
            }
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            toggleDock(apps.get(pos));
            return true;
        });
        grid.setVerticalScrollBarEnabled(false);
        root.addView(grid, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        grid.requestFocus();
        // The first selection happens before layout, so lift the initially selected cell once it exists.
        grid.post(() -> {
            View v = grid.getSelectedView();
            if (v != null && lifted == null) {
                lifted = ((ViewGroup) v).getChildAt(0);
                Apps.setLifted(this, lifted, true);
            }
        });
    }

    private void toggleDock(final Apps.App a) {
        final List<String> dock = Apps.dock(this);
        final boolean inDock = dock.contains(a.pkg);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(new String[] {inDock ? "Remove from dock" : "Add to dock"}, (d, w) -> {
                    if (inDock) dock.remove(a.pkg); else dock.add(a.pkg);
                    Apps.saveDock(this, dock);
                    android.widget.Toast.makeText(this, inDock ? "Removed from dock" : "Added to dock",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private int dp(float v) {
        return Apps.dp(this, v);
    }
}
