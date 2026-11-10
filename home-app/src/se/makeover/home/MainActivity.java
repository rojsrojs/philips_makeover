package se.makeover.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Favourites-only home screen: a grid of app banners in the user's own order. */
public final class MainActivity extends Activity {
    private static final int COLUMNS = 4;
    /** Share of the screen width used by the app grid; the image sits to the right of it. */
    private static final int APPS_PERCENT = 58;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private static final int MOVE_COLOR = 0xFFFFC107;

    private static final long SLIDESHOW_MS = 10_000;
    private static final ComponentName SOURCES =
        ComponentName.unflattenFromString("org.droidtv.channels/.sources.SourcesDrawerActivity");

    private static final String KEY_SELECTED = "bg_selected";
    private static final String KEY_INDEX = "bg_index";

    /** One launchable app as shown in the grid or the picker. */
    private static final class AppEntry {
        final String pkg;
        final CharSequence label;
        final Intent launch;
        final Drawable banner;
        final Drawable icon;

        AppEntry(String pkg, CharSequence label, Intent launch, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
        }

        @Override
        public String toString() {
            return label.toString();
        }
    }

    private final List<AppEntry> favorites = new ArrayList<>();
    private SharedPreferences prefs;
    private ImageView background;
    private ImageView fade;
    private ScrollView scroll;
    private GridLayout grid;
    private TextView hint;
    private TextView imageLabel;
    private int screenW;
    private int screenH;
    private int tileW;
    private int tileH;
    private int gap;
    private int radius;
    private int movingIndex = -1;
    private boolean appsChanged = true;
    private boolean resumed;
    private Bitmap backgroundBitmap;
    private String backgroundKey;
    private AlertDialog uploadDialog;

    private final Handler handler = new Handler();
    private final Runnable slideshow = new Runnable() {
        @Override
        public void run() {
            updateBackground(true);
            handler.postDelayed(this, SLIDESHOW_MS);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsChanged = true;
            if (resumed && movingIndex < 0) refreshFavorites();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        int appsW = screenW * APPS_PERCENT / 100;
        int pad = screenW * 5 / 100;
        gap = screenW * 2 / 100;
        tileW = (appsW - pad - gap - (COLUMNS - 1) * gap) / COLUMNS;
        tileH = tileW * 9 / 16;
        radius = tileW / 22;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // The image fills the screen height and hugs the right edge; wide images extend to the left.
        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.FIT_END);
        root.addView(background, MATCH, MATCH);

        // Second layer that fades in on top of the current image when it changes.
        fade = new ImageView(this);
        fade.setScaleType(ImageView.ScaleType.FIT_END);
        fade.setAlpha(0f);
        root.addView(fade, MATCH, MATCH);

        // Black behind the apps, softening into the image so wide photos slide in under the grid.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {Color.BLACK, Color.BLACK, Color.TRANSPARENT}));
        root.addView(shade, new FrameLayout.LayoutParams(screenW * (APPS_PERCENT + 8) / 100, MATCH));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        View topBar = buildTopBar();
        topBar.setPadding(pad, screenH * 5 / 100, gap, 0);
        column.addView(topBar, MATCH, WRAP);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(pad, gap * 2, gap, screenH * 10 / 100);
        grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        scroll.addView(grid, MATCH, WRAP);
        column.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(appsW, MATCH));

        // Number (or custom name) of the image being shown, in the bottom right corner.
        imageLabel = text("", screenH * 22 / 1000, 0xB3FFFFFF);
        imageLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams labelParams =
            new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        labelParams.rightMargin = gap;
        labelParams.bottomMargin = gap;
        root.addView(imageLabel, labelParams);

        hint = text("Flytta med pilarna  ·  Tryck OK när du är klar", screenH * 24 / 1000, Color.WHITE);
        hint.setBackground(rounded(0xCC000000, 999));
        hint.setPadding(gap, gap / 3, gap, gap / 3);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams =
            new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.bottomMargin = screenH * 4 / 100;
        root.addView(hint, hintParams);

        setContentView(root);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (appsChanged) refreshFavorites();
        updateBackground(true);
        handler.removeCallbacks(slideshow);
        handler.postDelayed(slideshow, SLIDESHOW_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(slideshow);
        if (movingIndex >= 0) stopMoving();
        if (uploadDialog != null) uploadDialog.dismiss();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: jump back to the first tile.
        if (resumed) {
            if (movingIndex >= 0) stopMoving();
            scroll.smoothScrollTo(0, 0);
            focusTile(0);
        }
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back to from the home screen.
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (movingIndex < 0) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveBy(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveBy(-COLUMNS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveBy(COLUMNS);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                if (down) stopMoving();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    // --- Favourites grid ---

    private void refreshFavorites() {
        appsChanged = false;
        favorites.clear();
        for (String pkg : Favorites.load(prefs)) {
            AppEntry app = loadApp(pkg, true);
            if (app != null) favorites.add(app);
        }
        rebuildGrid(0);
    }

    private void rebuildGrid(int focusIndex) {
        grid.removeAllViews();
        for (int i = 0; i < favorites.size(); i++) {
            grid.addView(createTile(i, favorites.get(i)), gridParams(i));
        }
        grid.addView(createAddTile(), gridParams(favorites.size()));
        focusTile(focusIndex);
    }

    private void focusTile(int index) {
        View tile = grid.getChildAt(Math.max(0, Math.min(index, grid.getChildCount() - 1)));
        if (tile != null) tile.requestFocus();
    }

    private GridLayout.LayoutParams gridParams(int index) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
            GridLayout.spec(index / COLUMNS), GridLayout.spec(index % COLUMNS));
        lp.width = tileW;
        lp.height = WRAP;
        lp.setMargins(0, 0, index % COLUMNS < COLUMNS - 1 ? gap : 0, gap / 2);
        return lp;
    }

    private View createTile(int index, AppEntry app) {
        FrameLayout card = newCard();
        if (app.banner != null) {
            ImageView banner = new ImageView(this);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(app.banner);
            card.addView(banner, MATCH, MATCH);
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            int size = tileH * 45 / 100;
            inner.addView(icon, new LinearLayout.LayoutParams(size, size));
            TextView name = text(app.label, tileH * 11 / 100, Color.WHITE);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            inner.addView(name, WRAP, WRAP);
            card.addView(inner, MATCH, MATCH);
        }
        View tile = wrapTile(card, app.label, index);
        tile.setOnClickListener(v -> {
            if (!tryStart(app.launch)) toast("Kunde inte öppna " + app.label);
        });
        tile.setOnLongClickListener(v -> {
            showTileMenu(index);
            return true;
        });
        return tile;
    }

    private View createAddTile() {
        FrameLayout card = newCard();
        TextView plus = text("+", tileH * 40 / 100, 0xCCFFFFFF);
        plus.setGravity(Gravity.CENTER);
        card.addView(plus, MATCH, MATCH);
        View tile = wrapTile(card, "Lägg till", -1);
        tile.setOnClickListener(v -> showPicker());
        return tile;
    }

    private FrameLayout newCard() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(0x33FFFFFF, radius));
        card.setClipToOutline(true);
        card.setElevation(radius / 2f);
        return card;
    }

    /** Puts a card above a caption that only shows when the tile has focus. */
    private View wrapTile(FrameLayout card, CharSequence label, int index) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setClipChildren(false);
        tile.setFocusable(true);
        tile.addView(card, new LinearLayout.LayoutParams(tileW, tileH));

        TextView caption = text(label, tileH * 10 / 100, Color.WHITE);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine();
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setAlpha(0f);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        captionParams.topMargin = tileH * 12 / 100;
        tile.addView(caption, captionParams);

        tile.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.1f : 1f;
            card.animate().scaleX(scale).scaleY(scale).translationZ(focused ? radius * 2 : 0)
                .setDuration(150).start();
            card.setForeground(focused ? ring(index == movingIndex ? MOVE_COLOR : Color.WHITE) : null);
            caption.animate().alpha(focused ? 1f : 0f).setDuration(150).start();
        });
        return tile;
    }

    private void showTileMenu(int index) {
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(favorites.get(index).label)
            .setItems(new CharSequence[] {"Flytta", "Tvinga avslutning", "Ta bort från favoriter"}, (dialog, which) -> {
                if (which == 0) {
                    startMoving(index);
                } else if (which == 1) {
                    AppEntry app = favorites.get(index);
                    forceStop(app.pkg, app.label, null);
                } else {
                    favorites.remove(index);
                    saveFavorites();
                    rebuildGrid(index);
                }
            })
            .show();
    }

    /**
     * Stops an app's processes. Android refuses for apps that are audible or visible (for example
     * music playing), so if the app is still running afterwards its system page opens instead,
     * where "Tvångsstoppa" always works.
     */
    private void forceStop(String pkg, CharSequence label, Runnable afterwards) {
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
        new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                // Check right away instead.
            }
            Boolean running = RunningApps.isRunning(this, pkg);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (Boolean.TRUE.equals(running)) {
                    toast(label + " kunde inte avslutas härifrån. Tryck Tvångsstoppa.");
                    tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                } else {
                    toast(label + " är avslutad");
                    if (afterwards != null) afterwards.run();
                }
            });
        }).start();
    }

    // --- Task manager ---

    private void showRunningApps() {
        AlertDialog loading = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Aktiva appar")
            .setMessage("Läser minnesanvändning …")
            .show();
        new Thread(() -> {
            List<RunningApps.Entry> apps = null;
            String error = null;
            try {
                apps = RunningApps.load(this);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
            List<RunningApps.Entry> result = apps;
            String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed() || !loading.isShowing()) return; // closed while loading
                loading.dismiss();
                showRunningAppsList(result, failure, memory);
            });
        }).start();
    }

    private void showRunningAppsList(List<RunningApps.Entry> apps, String error, ActivityManager.MemoryInfo memory) {
        String title = "Aktiva appar · " + memory.availMem / (1024 * 1024) + " av "
            + memory.totalMem / (1024 * 1024) + " MB ledigt";
        String message = null;
        if (error != null) {
            message = "Kunde inte läsa listan över appar (" + error + ").";
        } else if (apps == null) {
            message = "Hem behöver behörigheten DUMP för att se andra appar. "
                + "Den ges när Hem installeras med build.ps1 (se README).";
        } else if (apps.isEmpty()) {
            message = "Inga andra appar körs just nu.";
        }
        if (message != null) {
            new AlertDialog.Builder(this, DIALOG_THEME).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
            return;
        }

        PackageManager pm = getPackageManager();
        List<CharSequence> labels = new ArrayList<>();
        List<CharSequence> rows = new ArrayList<>();
        List<Drawable> icons = new ArrayList<>();
        for (RunningApps.Entry app : apps) {
            CharSequence label = app.pkg;
            Drawable icon = null;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(app.pkg, 0));
                icon = pm.getApplicationIcon(app.pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled since the list was read; show the package name.
            }
            labels.add(label);
            icons.add(icon);
            rows.add(label + "   ·   " + Math.round(app.pssKb / 1024f) + " MB");
        }

        int iconPx = screenH * 5 / 100;
        ArrayAdapter<CharSequence> adapter =
            new ArrayAdapter<CharSequence>(this, android.R.layout.select_dialog_item, rows) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, convertView, parent);
                    Drawable icon = icons.get(position);
                    if (icon != null) icon.setBounds(0, 0, iconPx, iconPx);
                    row.setCompoundDrawables(icon, null, null, null);
                    row.setCompoundDrawablePadding(iconPx / 2);
                    return row;
                }
            };
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setAdapter(adapter, (dialog, which) ->
                forceStop(apps.get(which).pkg, labels.get(which), this::showRunningApps))
            .setPositiveButton("Stäng", null)
            .show();
    }

    /**
     * Opens Android's About page ("Om"), where "Starta om" is the first, already selected item.
     * Apps may not restart the TV themselves: the REBOOT permission is system-only.
     */
    private void openRestart() {
        if (!tryStart(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))) {
            toast("Starta om via Inställningar → Android-inställningar → Om");
        }
    }

    private void startMoving(int index) {
        movingIndex = index;
        hint.setVisibility(View.VISIBLE);
        rebuildGrid(index);
    }

    private void stopMoving() {
        int index = movingIndex;
        movingIndex = -1;
        hint.setVisibility(View.GONE);
        saveFavorites();
        rebuildGrid(index);
    }

    private void moveBy(int delta) {
        int target = movingIndex + delta;
        int last = favorites.size() - 1;
        // Moving down from a full row into a shorter last row lands on the last tile.
        if (delta == COLUMNS && target > last && movingIndex / COLUMNS < last / COLUMNS) target = last;
        if (target < 0 || target > last || target == movingIndex) return;
        favorites.add(target, favorites.remove(movingIndex));
        movingIndex = target;
        rebuildGrid(target);
    }

    private void saveFavorites() {
        List<String> packages = new ArrayList<>();
        for (AppEntry app : favorites) packages.add(app.pkg);
        Favorites.save(prefs, packages);
    }

    private void showPicker() {
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (AppEntry app : favorites) seen.add(app.pkg);

        PackageManager pm = getPackageManager();
        List<AppEntry> apps = new ArrayList<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String pkg = info.activityInfo.packageName;
                if (!seen.add(pkg)) continue;
                AppEntry app = loadApp(pkg, false);
                if (app != null) apps.add(app);
            }
        }
        if (apps.isEmpty()) {
            toast("Alla appar är redan favoriter");
            return;
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));

        int iconPx = screenH * 5 / 100;
        ArrayAdapter<AppEntry> adapter =
            new ArrayAdapter<AppEntry>(this, android.R.layout.select_dialog_item, apps) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, convertView, parent);
                    Drawable icon = getItem(position).icon;
                    if (icon != null) icon.setBounds(0, 0, iconPx, iconPx);
                    row.setCompoundDrawables(icon, null, null, null);
                    row.setCompoundDrawablePadding(iconPx / 2);
                    return row;
                }
            };
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Lägg till favorit")
            .setAdapter(adapter, (dialog, which) -> {
                AppEntry app = loadApp(apps.get(which).pkg, true);
                if (app == null) return;
                favorites.add(app);
                saveFavorites();
                rebuildGrid(favorites.size() - 1);
            })
            .show();
    }

    private AppEntry loadApp(String pkg, boolean withBanner) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        ResolveInfo resolved = pm.resolveActivity(launch, 0);
        if (resolved == null) return null;
        ActivityInfo info = resolved.activityInfo;
        return new AppEntry(pkg, info.loadLabel(pm), launch,
            withBanner ? info.loadBanner(pm) : null, info.loadIcon(pm));
    }

    // --- Top bar ---

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 7 / 100f);
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(6, 0, 2, 0x99000000);
        clock.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 22 / 1000f);
        date.setTextColor(0xCCFFFFFF);
        date.setShadowLayer(4, 0, 1, 0x99000000);
        clock.addView(date);
        bar.addView(clock, new LinearLayout.LayoutParams(0, WRAP, 1f));

        bar.addView(pill("Källor", v -> {
            if (!tryStart(new Intent(Intent.ACTION_MAIN).setComponent(SOURCES))) toast("Kunde inte öppna källor");
        }));
        bar.addView(pill("Aktiva appar", v -> showRunningApps()));
        bar.addView(pill("Slideshow", v -> showBackgroundMenu()));
        bar.addView(pill("Inställningar", v -> {
            if (!tryStart(new Intent(Settings.ACTION_SETTINGS))) toast("Kunde inte öppna inställningar");
        }));
        bar.addView(pill("Om", v -> openRestart()));
        return bar;
    }

    private TextView pill(String label, View.OnClickListener onClick) {
        TextView pill = text(label, screenH * 22 / 1000, Color.WHITE);
        pill.setShadowLayer(0, 0, 0, 0);
        int vertical = screenH * 12 / 1000;
        int horizontal = vertical * 17 / 10;
        pill.setPadding(horizontal, vertical, horizontal, vertical);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, rounded(Color.WHITE, 999));
        bg.addState(new int[0], rounded(0x33FFFFFF, 999));
        pill.setBackground(bg);
        pill.setTextColor(new ColorStateList(
            new int[][] {{android.R.attr.state_focused}, {}}, new int[] {Color.BLACK, Color.WHITE}));
        pill.setFocusable(true);
        pill.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = gap / 2;
        pill.setLayoutParams(lp);
        return pill;
    }

    // --- Background ---

    /** No selected images: black. One: that image. Several: a slideshow, advanced on each call. */
    private void updateBackground(boolean advance) {
        Set<String> selected = prefs.getStringSet(KEY_SELECTED, Collections.emptySet());
        List<File> files = new ArrayList<>();
        for (File file : Backgrounds.list(this)) {
            if (selected.contains(file.getName())) files.add(file);
        }
        if (files.isEmpty()) {
            showDefaultBackground();
            return;
        }
        int index = Math.floorMod(prefs.getInt(KEY_INDEX, -1) + (advance ? 1 : 0), files.size());
        prefs.edit().putInt(KEY_INDEX, index).apply();
        File pick = files.get(index);

        String key = pick.getPath() + "@" + pick.lastModified();
        if (key.equals(backgroundKey)) return;
        backgroundKey = key;
        File file = pick;
        new Thread(() -> {
            Bitmap bitmap = Backgrounds.decode(file, screenW, screenH);
            runOnUiThread(() -> showBitmap(bitmap, Backgrounds.label(file)));
        }).start();
    }

    /** Cross-fades to the new image. Old bitmaps are left to the GC so none is recycled while drawn. */
    private void showBitmap(Bitmap bitmap, String label) {
        if (isDestroyed()) return;
        if (bitmap == null) {
            showDefaultBackground();
            return;
        }
        backgroundBitmap = bitmap;
        imageLabel.setText(label);
        imageLabel.setVisibility(View.VISIBLE);
        fade.animate().cancel();
        fade.setImageBitmap(bitmap);
        fade.setAlpha(0f);
        fade.animate().alpha(1f).setDuration(1200).withEndAction(() -> {
            background.setImageBitmap(bitmap);
            fade.setAlpha(0f);
            fade.setImageDrawable(null);
        }).start();
    }

    private void showDefaultBackground() {
        backgroundKey = null;
        backgroundBitmap = null;
        fade.animate().cancel();
        fade.setAlpha(0f);
        fade.setImageDrawable(null);
        background.setImageDrawable(null);
        imageLabel.setVisibility(View.GONE);
    }

    private void showBackgroundMenu() {
        CharSequence[] items = {"Välj bilder", "Ladda upp från mobilen", "Byt namn", "Ta bort bilder"};
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Slideshow")
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    showSelectImages();
                } else if (which == 1) {
                    showUploadDialog();
                } else if (which == 2) {
                    showRenameImages();
                } else {
                    showDeleteImages();
                }
            })
            .show();
    }

    private void showRenameImages() {
        List<File> files = Backgrounds.list(this);
        if (files.isEmpty()) {
            showNoImages();
            return;
        }
        CharSequence[] names = new CharSequence[files.size()];
        for (int i = 0; i < files.size(); i++) names[i] = Backgrounds.displayName(files.get(i));
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Byt namn · välj bild")
            .setItems(names, (dialog, which) -> showRenameDialog(files.get(which)))
            .show();
    }

    private void showRenameDialog(File file) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(Backgrounds.displayName(file));
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(gap, gap / 2, gap, 0);
        box.addView(input, MATCH, WRAP);

        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Nytt namn")
            .setView(box)
            .setPositiveButton("Spara", (dialog, which) -> {
                File renamed = Backgrounds.rename(file, input.getText().toString());
                if (renamed == null) {
                    toast("Kunde inte byta namn");
                    return;
                }
                Set<String> selected = new HashSet<>(prefs.getStringSet(KEY_SELECTED, Collections.emptySet()));
                if (selected.remove(file.getName())) {
                    selected.add(renamed.getName());
                    prefs.edit().putStringSet(KEY_SELECTED, selected).apply();
                }
                updateBackground(false);
            })
            .setNegativeButton("Avbryt", null)
            .show();
    }

    private void showSelectImages() {
        List<File> files = Backgrounds.list(this);
        if (files.isEmpty()) {
            showNoImages();
            return;
        }
        Set<String> selected = prefs.getStringSet(KEY_SELECTED, Collections.emptySet());
        showImageChecklist("Välj bilder · flera bilder = bildspel", files, selected, "Spara", chosen -> {
            prefs.edit().putStringSet(KEY_SELECTED, chosen).apply();
            updateBackground(false);
        });
    }

    private void showDeleteImages() {
        List<File> files = Backgrounds.list(this);
        if (files.isEmpty()) {
            showNoImages();
            return;
        }
        showImageChecklist("Ta bort bilder", files, Collections.emptySet(), "Ta bort", chosen -> {
            if (chosen.isEmpty()) return;
            for (File file : files) {
                if (chosen.contains(file.getName())) file.delete();
            }
            Set<String> selected = new HashSet<>(prefs.getStringSet(KEY_SELECTED, Collections.emptySet()));
            selected.removeAll(chosen);
            prefs.edit().putStringSet(KEY_SELECTED, selected).apply();
            updateBackground(false);
            toast(chosen.size() == 1 ? "1 bild borttagen" : chosen.size() + " bilder borttagna");
        });
    }

    private void showNoImages() {
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Slideshow")
            .setMessage("Det finns inga bilder på TV:n ännu. "
                + "Välj \"Ladda upp från mobilen\" för att lägga till.")
            .setPositiveButton("OK", null)
            .show();
    }

    /** A checklist of images with a select-all toggle; onAction gets the checked file names. */
    private void showImageChecklist(String title, List<File> files, Set<String> initiallyChecked,
                                    String action, Consumer<Set<String>> onAction) {
        CharSequence[] names = new CharSequence[files.size()];
        boolean[] checked = new boolean[files.size()];
        for (int i = 0; i < files.size(); i++) {
            names[i] = Backgrounds.displayName(files.get(i));
            checked[i] = initiallyChecked.contains(files.get(i).getName());
        }

        AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                checked[which] = isChecked;
                holder[0].getButton(AlertDialog.BUTTON_NEUTRAL).setText(toggleLabel(checked));
            })
            .setPositiveButton(action, (d, which) -> {
                Set<String> chosen = new HashSet<>();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) chosen.add(files.get(i).getName());
                }
                onAction.accept(chosen);
            })
            .setNegativeButton("Avbryt", null)
            .setNeutralButton(toggleLabel(checked), null)
            .create();
        holder[0] = dialog;
        // Set the toggle's listener after show so the button does not close the dialog.
        dialog.setOnShowListener(d -> {
            Button toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            toggle.setOnClickListener(v -> {
                boolean value = !allChecked(checked);
                ListView list = dialog.getListView();
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = value;
                    list.setItemChecked(i, value);
                }
                toggle.setText(toggleLabel(checked));
            });
        });
        dialog.show();
    }

    /** Runs a small upload page for phones on the same network while this dialog is open. */
    private void showUploadDialog() {
        String ip = localIpAddress();
        if (ip == null) {
            toast("TV:n verkar inte vara ansluten till nätverket");
            return;
        }
        String pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10_000));
        TextView body = text("", screenH * 26 / 1000, Color.WHITE);
        body.setShadowLayer(0, 0, 0, 0);
        body.setPadding(gap, gap / 2, gap, 0);
        String[] address = new String[1];
        int[] received = {0};

        UploadServer server;
        try {
            server = new UploadServer(this, pin, name -> runOnUiThread(() -> {
                received[0]++;
                body.setText(uploadText(address[0], pin, received[0]));
            }));
            address[0] = "http://" + ip + ":" + server.start();
        } catch (IOException e) {
            toast("Kunde inte starta uppladdningen");
            return;
        }
        body.setText(uploadText(address[0], pin, 0));

        // The QR code opens the upload page with the code already filled in.
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(QrCode.render(address[0] + "/?pin=" + pin, Math.max(4, screenH / 150)));
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(gap, gap / 2, 0, 0);
        content.addView(qr, WRAP, WRAP);
        content.addView(body, new LinearLayout.LayoutParams(0, WRAP, 1f));

        uploadDialog = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Ladda upp från mobilen")
            .setView(content)
            .setPositiveButton("Klar", null)
            .create();
        uploadDialog.setOnDismissListener(d -> {
            server.stop();
            uploadDialog = null;
            updateBackground(false);
        });
        uploadDialog.show();
    }

    private static CharSequence uploadText(String address, String pin, int received) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        appendBig(text, "Skanna QR-koden med mobilens kamera");
        text.append("\n\nEller öppna ").append(address).append(" och skriv in koden ");
        appendBig(text, pin);
        text.append("\n\nMobilen måste vara ansluten till samma nätverk som TV:n.\n\n");
        text.append("Mottagna bilder: ").append(String.valueOf(received));
        return text;
    }

    private static void appendBig(SpannableStringBuilder text, String value) {
        int start = text.length();
        text.append(value);
        text.setSpan(new RelativeSizeSpan(1.3f), start, text.length(), 0);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), 0);
    }

    private static String localIpAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            // Treated as not connected.
        }
        return null;
    }

    private static boolean allChecked(boolean[] checked) {
        for (boolean value : checked) {
            if (!value) return false;
        }
        return true;
    }

    private static String toggleLabel(boolean[] checked) {
        return allChecked(checked) ? "Avmarkera alla" : "Markera alla";
    }

    // --- Helpers ---

    private boolean tryStart(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private TextView text(CharSequence value, int px, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        view.setTextColor(color);
        view.setShadowLayer(4, 0, 1, 0x99000000);
        return view;
    }

    private static GradientDrawable rounded(int color, float cornerRadius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(cornerRadius);
        return drawable;
    }

    private GradientDrawable ring(int color) {
        GradientDrawable drawable = rounded(Color.TRANSPARENT, radius);
        drawable.setStroke(Math.max(3, tileW / 80), color);
        return drawable;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
