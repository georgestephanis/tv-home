package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private final List<String> dockApps = new ArrayList<>(); // saved order, else config.xml
    private String moving; // dock app being rearranged, or null
    private ObjectAnimator wiggle;
    private final Set<String> hidden = new HashSet<>();
    private final Set<String> userHidden = new HashSet<>(); // moved down out of All apps
    private boolean showHidden;
    private int cols, dockCols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> hiddenCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private MaxHeightScrollView dockScroll;
    private FrameLayout hero;
    private LinearLayout grid, hiddenGrid;
    private TextView hiddenToggle;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, appsLabel, hint;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // The Search pill comes and goes with the system search app being enabled.
            if (searchIntent() != null != hasSearch) recreate(); else buildApps();
        }
    };
    private boolean hasSearch;
    private FrameLayout root;
    private View settingsPanel;
    private int settingsPos = -1, clickedPos;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String saved = getPreferences(MODE_PRIVATE).getString("dock", null);
        dockApps.addAll(Arrays.asList(saved != null ? TextUtils.split(saved, ",")
                : getResources().getStringArray(R.array.dock_apps)));
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        userHidden.addAll(Arrays.asList(TextUtils.split(getPreferences(MODE_PRIVATE).getString("hidden", ""), ",")));
        hidden.add(getPackageName());
        cols = getPreferences(MODE_PRIVATE).getInt("columns", getResources().getInteger(R.integer.columns));
        dockCols = getPreferences(MODE_PRIVATE).getInt("dock_columns", getResources().getInteger(R.integer.dock_columns));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();
        if (b != null && b.getInt("settings", -1) >= 0) showSettings(b.getInt("settings"), false);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        updateClock();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        stopMoving();
    }

    @Override
    protected void onStop() {
        super.onStop();
        collapseHidden();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app. From another app, keep focus where it was.
        stopMoving();
        collapseHidden();
        if (!hasWindowFocus()) return; // the activity is paused for the intent either way
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (settingsPanel != null) { closeSettings(); return; }
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        // Title with a small move-mode hint beside it, sharing the baseline.
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.addView(title);
        hint = text(11, "sans-serif-medium", 0x99FFFFFF);
        hint.setLetterSpacing(0.22f);
        hint.setVisibility(View.GONE);
        titleRow.addView(hint, llp(-2, -2, dp(20), 0, 0, 0));
        bottom.addView(titleRow, llp(-2, -2, dp(8), 0, 0, dp(14)));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.VERTICAL); // rows of dockCols tiles
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        // Enough dock rows to reach the clock scroll inside the band, fading out at the edges.
        dockScroll = new MaxHeightScrollView(this);
        dockScroll.setBackground(band);
        dockScroll.setClipToOutline(true); // its parents let children overflow
        dockScroll.setVerticalScrollBarEnabled(false);
        dockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dockScroll.setVerticalFadingEdgeEnabled(true);
        dockScroll.setFadingEdgeLength(dp(48));
        dockScroll.addView(dockRow, new FrameLayout.LayoutParams(-1, -2));
        bottom.addView(dockScroll, new LinearLayout.LayoutParams(-1, -2));
        View header = hero.getChildAt(0);
        header.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        title.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        dockScroll.max = screenH - dp(36) - header.getMeasuredHeight() - dp(24)
                - title.getMeasuredHeight() - dp(14) - dp(30);

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), 0));

        // Shown only when something is hidden; opens the hidden apps grid below it.
        hiddenToggle = text(11, "sans-serif-medium", 0x99FFFFFF);
        hiddenToggle.setLetterSpacing(0.22f);
        hiddenToggle.setGravity(Gravity.CENTER);
        hiddenToggle.setPadding(dp(14), 0, dp(14), 0);
        hiddenToggle.setFocusable(true);
        hiddenToggle.setId(View.generateViewId());
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(dp(15));
        tbg.setStroke(dp(1), 0x33FFFFFF);
        hiddenToggle.setBackground(tbg);
        hiddenToggle.setOnFocusChangeListener((v, has) -> {
            tbg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            hiddenToggle.setTextColor(has ? 0xFF111111 : 0x99FFFFFF);
            if (has) { lastFocusedId = v.getId(); scrollTo(v.getTop()); }
        });
        hiddenToggle.setOnClickListener(v -> { showHidden = !showHidden; buildApps(); });
        content.addView(hiddenToggle, llp(-2, dp(30), dp(60), 0, 0, dp(26)));

        hiddenGrid = new LinearLayout(this);
        hiddenGrid.setOrientation(LinearLayout.VERTICAL);
        hiddenGrid.setClipChildren(false);
        hiddenGrid.setClipToPadding(false);
        content.addView(hiddenGrid, llp(-1, -2, dp(68), 0, dp(68), 0));
        content.setPadding(0, 0, 0, dp(160)); // room to centre the last row
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView settings = pill("Settings", "⚙");
        settings.setOnClickListener(v -> showSettings(0, true));
        List<TextView> top = new ArrayList<>();
        Intent search = searchIntent();
        hasSearch = search != null;
        if (hasSearch) {
            TextView s = pill("Search", "⌕");
            s.setOnClickListener(v -> launch(search));
            top.add(s);
        }
        top.add(settings);
        for (TextView p : top) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        View f = getCurrentFocus();
        String focusedPkg = f != null && f.getTag() instanceof App ? ((App) f.getTag()).pkg : null;
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> hid = new ArrayList<>();
        for (String p : userHidden) if (byPkg.containsKey(p)) hid.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        Collections.sort(hid, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        hiddenGrid.removeAllViews();
        dockCards.clear();
        gridCards.clear();
        hiddenCards.clear();

        LinearLayout row = null;
        for (int i = 0; i < dock.size(); i++) {
            if (i % dockCols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                dockRow.addView(row, llp(-1, -2, 0, i == 0 ? 0 : dp(18), 0, 0));
            }
            View tile = tile(dock.get(i), dockCards);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % dockCols == 0 ? 0 : dp(10), 0, i % dockCols == dockCols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        padRow(row, dock.size(), dockCols);

        fillGrid(grid, rest, gridCards);
        if (hid.isEmpty()) showHidden = false;
        if (showHidden) fillGrid(hiddenGrid, hid, hiddenCards);
        hiddenToggle.setText(showHidden ? "HIDE HIDDEN APPS" : "SHOW HIDDEN APPS  \u00B7  " + hid.size());
        hiddenToggle.setVisibility(hid.isEmpty() ? View.GONE : View.VISIBLE);
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        else title.setText("");
        // View ids are new after a rebuild, so refocus by package; keep a move going across it.
        if (focusApp(moving != null ? moving : focusedPkg)) return;
        if (moving != null) stopMoving(); // the app being moved was uninstalled
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    private void fillGrid(LinearLayout into, List<App> apps, List<View> cards) {
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                into.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(apps.get(i), cards);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        padRow(row, apps.size(), cols);
    }

    private void collapseHidden() {
        if (showHidden) { showHidden = false; buildApps(); }
    }

    /** Pad the last row so tiles keep their width. */
    private void padRow(LinearLayout row, int n, int perRow) {
        if (row != null) for (int i = n % perRow; i != 0 && i < perRow; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == perRow - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, List<View> cards) {
        boolean inDock = cards == dockCards;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setContentDescription(app.label);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnLongClickListener(v -> { moving = app.pkg; focusApp(app.pkg); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        cards.add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d, dockCols) - 1).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        int below = hiddenToggle.getVisibility() == View.VISIBLE ? hiddenToggle.getId() : View.NO_ID;
        int lastDockRowStart = d == 0 ? 0 : (d - 1) / dockCols * dockCols;
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            int col = i % dockCols;
            c.setNextFocusUpId(i < dockCols ? topId : dockCards.get(i - dockCols).getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(col == dockCols - 1 || i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(i + dockCols < d ? dockCards.get(i + dockCols).getId()
                    : i < lastDockRowStart ? dockCards.get(d - 1).getId()
                    : g > 0 ? gridCards.get(Math.min(col * cols / dockCols, g - 1)).getId()
                    : below != View.NO_ID ? below : c.getId());
        }
        wireGrid(gridCards, col -> d > 0 ? dockCards.get(Math.min(lastDockRowStart + col * dockCols / cols, d - 1)).getId() : topId, below);
        View t = hiddenToggle;
        t.setNextFocusUpId(g > 0 ? gridCards.get((g - 1) / cols * cols).getId() : d > 0 ? dockCards.get(lastDockRowStart).getId() : topId);
        t.setNextFocusDownId(hiddenCards.isEmpty() ? t.getId() : hiddenCards.get(0).getId());
        t.setNextFocusLeftId(t.getId());
        t.setNextFocusRightId(t.getId());
        wireGrid(hiddenCards, col -> t.getId(), View.NO_ID);
    }

    /** A grid of cols per row; Up from the first row and Down from the last row go to the given ids. */
    private void wireGrid(List<View> cards, java.util.function.IntUnaryOperator upFromCol, int belowId) {
        int g = cards.size(), lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = cards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0 ? upFromCol.applyAsInt(col) : cards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? cards.get(j + cols).getId()
                    : r < lastRow ? cards.get(g - 1).getId() : belowId != View.NO_ID ? belowId : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : cards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : cards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        View section = (View) row.getParent();
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockScroll.getParent();
        if (section == grid && grid.indexOfChild(row) == 0)
            scroll.smoothScrollTo(0, hero.getTop() + band.getTop() + dockScroll.getTop() - dp(22));
        else scrollTo(section.getTop() + row.getTop());
    }

    /** Scroll so content y sits a little above the middle of the screen. */
    private void scrollTo(int y) {
        int target = y - (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- editing the dock

    /** Down past the last dock row: drop the moving app into All apps, still moving so Up can undo it. */
    private void removeMoving() {
        dockApps.remove(moving);
        saveDock();
    }

    /** Down from All apps: hide the moving app, opening the hidden section so it can keep moving there. */
    private void hideMoving() {
        userHidden.add(moving);
        showHidden = true;
        saveDock();
    }

    private void stopMoving() {
        moving = null;
        hint.setVisibility(View.GONE);
        if (wiggle == null) return;
        View c = (View) wiggle.getTarget();
        wiggle.cancel();
        wiggle = null;
        c.setRotation(0);
        ViewGroup box = (ViewGroup) c.getParent();
        if (box.getChildCount() > 1) ((TextView) box.getChildAt(1)).setText(((App) c.getTag()).label);
    }

    private boolean movingOnLastRow() {
        int i = dockPackages().indexOf(moving);
        return i >= 0 && i / dockCols == (dockCards.size() - 1) / dockCols;
    }

    /** Move the app being rearranged by delta places among the visible dock tiles. */
    private void moveBy(int delta) {
        List<String> order = dockPackages();
        // Past the end clamps to last place (Down into a short last row); before the start does nothing.
        int i = order.indexOf(moving), j = Math.min(order.size() - 1, i + delta);
        if (i < 0 || j < 0 || i == j) return;
        order.add(j, order.remove(i));
        for (String p : dockApps) if (!order.contains(p)) order.add(p); // keep uninstalled favourites
        dockApps.clear();
        dockApps.addAll(order);
        saveDock();
    }

    private List<String> dockPackages() {
        List<String> l = new ArrayList<>();
        for (View c : dockCards) l.add(((App) c.getTag()).pkg);
        return l;
    }

    private void saveDock() {
        getPreferences(MODE_PRIVATE).edit().putString("dock", TextUtils.join(",", dockApps))
                .putString("hidden", TextUtils.join(",", userHidden)).apply();
        buildApps();
    }

    /** Focus an app's tile by package (view ids change on every rebuild); wiggle it if it's moving. */
    private boolean focusApp(String pkg) {
        if (pkg == null) return false;
        List<View> all = new ArrayList<>(dockCards);
        all.addAll(gridCards);
        all.addAll(hiddenCards);
        for (View c : all) {
            App a = (App) c.getTag();
            if (!a.pkg.equals(pkg)) continue;
            c.requestFocus();
            boolean inDock = dockCards.contains(c);
            // Set explicitly: refocusing an already-focused tile doesn't fire its focus listener.
            if (inDock) title.setText(a.label);
            if (!pkg.equals(moving)) return true;
            if (inDock) {
                boolean last = movingOnLastRow();
                hint.setText("\u25C0 \u25B6" + (dockCards.indexOf(c) >= dockCols ? " \u25B2" : "") + (last ? "" : " \u25BC")
                        + " MOVE" + (last ? "  \u00B7  \u25BC REMOVE" : "") + "  \u00B7  OK DONE");
                hint.setVisibility(View.VISIBLE);
            } else {
                // The tile's own name label, since the dock title is scrolled away.
                ((TextView) ((ViewGroup) c.getParent()).getChildAt(1)).setText(hiddenCards.contains(c)
                        ? "\u25B2 UNHIDE" : "\u25B2 FAVORITE   \u25BC HIDE");
            }
            if (wiggle != null) wiggle.cancel(); // its tile was replaced by the rebuild
            wiggle = ObjectAnimator.ofFloat(c, View.ROTATION, -2f, 2f);
            wiggle.setDuration(220);
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.start();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Google TV's search, or null if no enabled app handles it or it's switched off here. */
    private Intent searchIntent() {
        return getPreferences(MODE_PRIVATE).getBoolean("search", true) ? systemSearch() : null;
    }

    private Intent systemSearch() {
        Intent i = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        return getPackageManager().resolveActivity(i, 0) != null ? i : null;
    }

    // ---------------------------------------------------------------- settings

    /** Side panel with TV settings plus the launcher's own options. Changes recreate the activity and reopen it on the same row. */
    private void showSettings(int pos, boolean animate) {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        Intent home = new Intent(Settings.ACTION_HOME_SETTINGS);
        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        rows.add(new String[]{"TV settings", "›"});
        actions.add(() -> { closeSettings(); launch(new Intent(Settings.ACTION_SETTINGS)); });
        rows.add(new String[]{"Columns", String.valueOf(cols)});
        actions.add(() -> change(prefs.edit().putInt("columns", cols >= 8 ? 4 : cols + 1)));
        rows.add(new String[]{"Dock columns", String.valueOf(dockCols)});
        actions.add(() -> change(prefs.edit().putInt("dock_columns", dockCols >= 8 ? 4 : dockCols + 1)));
        if (systemSearch() != null) { // no system search: no pill, nothing to toggle
            boolean on = prefs.getBoolean("search", true);
            rows.add(new String[]{"Search button", on ? "On" : "Off"});
            actions.add(() -> change(prefs.edit().putBoolean("search", !on)));
        }
        if (getPackageManager().resolveActivity(home, 0) != null) {
            rows.add(new String[]{"Default home app", "›"});
            actions.add(() -> { closeSettings(); launch(home); });
        }

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(36), dp(28), dp(28));
        panel.setBackgroundColor(0xF0141414);
        TextView title = text(24, "sans-serif-light", Color.WHITE);
        title.setText("Settings");
        panel.addView(title, llp(-2, -2, dp(12), 0, 0, dp(20)));
        List<View> views = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            int which = k;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setFocusable(true);
            row.setId(View.generateViewId());
            TextView label = text(15, "sans-serif", 0xE6FFFFFF), value = text(15, "sans-serif-medium", 0x99FFFFFF);
            label.setText(rows.get(k)[0]);
            value.setText(rows.get(k)[1]);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(value);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            row.setBackground(bg);
            row.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xF2FFFFFF : 0);
                label.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
                value.setTextColor(has ? 0xFF555555 : 0x99FFFFFF);
            });
            row.setOnClickListener(v -> { clickedPos = which; actions.get(which).run(); });
            row.setNextFocusLeftId(row.getId());
            row.setNextFocusRightId(row.getId());
            panel.addView(row, llp(-1, dp(48), 0, 0, 0, dp(4)));
            views.add(row);
        }
        views.get(0).setNextFocusUpId(views.get(0).getId());
        views.get(views.size() - 1).setNextFocusDownId(views.get(views.size() - 1).getId());
        overlay.addView(panel, new FrameLayout.LayoutParams(dp(360), -1, Gravity.END));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        settingsPanel = overlay;
        views.get(Math.min(pos, views.size() - 1)).requestFocus();
        if (animate) {
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(200).start();
            panel.setTranslationX(dp(360));
            panel.animate().translationX(0).setInterpolator(new DecelerateInterpolator(2f)).setDuration(260).start();
        }
    }

    private void closeSettings() {
        View overlay = settingsPanel;
        settingsPanel = null;
        overlay.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(overlay)).start();
        ((ViewGroup) overlay).getChildAt(0).animate().translationX(dp(360)).setDuration(200).start();
        if (!pills.isEmpty()) pills.get(pills.size() - 1).requestFocus();
    }

    private void change(SharedPreferences.Editor e) {
        e.apply();
        settingsPos = clickedPos;
        recreate();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("settings", settingsPos);
        settingsPos = -1;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Rearranging: in the dock, arrows move the app, Down off the last row removes it; in All apps,
        // Up adds it to the dock, Down hides it; in Hidden, Up unhides it. It keeps moving through each step.
        // Any other key finishes. Both key actions are eaten so the
        // key-up can't click the tile or trigger Back.
        if (moving != null) {
            int k = e.getKeyCode();
            int delta = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                    : k == KeyEvent.KEYCODE_DPAD_UP ? -dockCols : k == KeyEvent.KEYCODE_DPAD_DOWN ? dockCols : 0;
            boolean done = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK;
            if (delta != 0 || done) {
                // Repeats are the long-press that started the move still being held.
                if (e.getAction() == KeyEvent.ACTION_DOWN && (delta != 0 || e.getRepeatCount() == 0)) {
                    boolean inDock = dockPackages().contains(moving), inHidden = userHidden.contains(moving);
                    if (inDock && delta == dockCols && movingOnLastRow()) removeMoving();
                    else if (inDock && delta != 0) moveBy(delta);
                    else if (inHidden && delta == -dockCols) { userHidden.remove(moving); saveDock(); }
                    else if (!inHidden && delta == -dockCols) { dockApps.add(moving); saveDock(); }
                    else if (!inHidden && delta == dockCols) hideMoving();
                    else { String p = moving; stopMoving(); focusApp(p); }
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView no taller than max. */
    static final class MaxHeightScrollView extends android.widget.ScrollView {
        int max = Integer.MAX_VALUE;
        MaxHeightScrollView(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
