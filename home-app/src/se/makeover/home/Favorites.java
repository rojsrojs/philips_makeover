package se.makeover.home;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The ordered list of favourite package names, stored in shared preferences. */
final class Favorites {
    private static final String KEY = "favorites";

    /** Used until the user changes the list for the first time. */
    private static final String[] DEFAULTS = {
        "com.google.android.youtube.tv",
        "se.tv4.tv4playtab",
        "com.viaplay.android",
        "com.wbd.stream",
        "se.svt.android.svtplay",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.teliasonera.telia.playplus",
        "com.spotify.tv.android",
        "com.apple.atve.androidtv.appletv",
        "tv.twitch.android.app",
    };

    private Favorites() {}

    static List<String> load(SharedPreferences prefs) {
        String stored = prefs.getString(KEY, null);
        if (stored == null) return new ArrayList<>(Arrays.asList(DEFAULTS));
        if (stored.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(stored.split(",")));
    }

    static void save(SharedPreferences prefs, List<String> packages) {
        prefs.edit().putString(KEY, TextUtils.join(",", packages)).apply();
    }
}
