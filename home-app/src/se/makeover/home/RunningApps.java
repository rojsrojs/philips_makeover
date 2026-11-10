package se.makeover.home;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Running user-facing apps and their memory, a small "task manager".
 * Needs android.permission.DUMP and usage access (GET_USAGE_STATS), which build.ps1 grants over adb;
 * without them Android only shows our own process.
 */
final class RunningApps {
    /** One running app: all its processes added together. */
    static final class Entry {
        final String pkg;
        final int[] pids;
        int pssKb;

        Entry(String pkg, int[] pids) {
            this.pkg = pkg;
            this.pids = pids;
        }
    }

    /** "Proc #12: cch  B/ /CE  trm: 0 6834:com.example.app:remote/u0a93 (cch-empty)"; small numbers are "Proc # 5:". */
    private static final Pattern PROCESS = Pattern.compile("(?:Proc|PERS) # *\\d+:.*?\\s(\\d+):([A-Za-z0-9_.:]+)/\\S+");
    /** System packages that are never listed, so nothing the TV depends on can be stopped from here. */
    private static final String[] PROTECTED = {"org.droidtv.", "com.mediatek.", "com.android.", "android"};

    private RunningApps() {}

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    /** Running launchable apps, largest first, or null without the DUMP permission. */
    static List<Entry> load(Context context) throws IOException {
        if (!hasPermission(context)) return null;
        PackageManager pm = context.getPackageManager();
        Map<String, List<Integer>> pidsByPackage = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> process : processes().entrySet()) {
            String pkg = process.getValue().split(":")[0];
            if (pkg.equals(context.getPackageName()) || isProtected(pkg)) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
            pidsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(process.getKey());
        }

        List<Entry> entries = new ArrayList<>();
        List<Integer> allPids = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> app : pidsByPackage.entrySet()) {
            int[] pids = new int[app.getValue().size()];
            for (int i = 0; i < pids.length; i++) pids[i] = app.getValue().get(i);
            entries.add(new Entry(app.getKey(), pids));
            allPids.addAll(app.getValue());
        }
        if (entries.isEmpty()) return entries;

        int[] pids = new int[allPids.size()];
        for (int i = 0; i < pids.length; i++) pids[i] = allPids.get(i);
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Debug.MemoryInfo[] memory = am.getProcessMemoryInfo(pids);
        int index = 0;
        for (Entry entry : entries) {
            for (int ignored : entry.pids) entry.pssKb += memory[index++].getTotalPss();
        }
        entries.sort((a, b) -> b.pssKb - a.pssKb);
        return entries;
    }

    /** Whether any process of the package is still running; null if that cannot be checked. */
    static Boolean isRunning(Context context, String pkg) {
        if (!hasPermission(context)) return null;
        try {
            for (String process : processes().values()) {
                if (process.equals(pkg) || process.startsWith(pkg + ":")) return true;
            }
            return false;
        } catch (IOException e) {
            return null;
        }
    }

    /** pid -> process name for every app process, from "dumpsys activity o". */
    private static Map<Integer, String> processes() throws IOException {
        Process dumpsys = new ProcessBuilder("/system/bin/dumpsys", "activity", "o")
            .redirectErrorStream(true).start();
        Map<Integer, String> out = new LinkedHashMap<>();
        String firstLine = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(dumpsys.getInputStream()))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (firstLine == null && !line.trim().isEmpty()) firstLine = line.trim();
                Matcher match = PROCESS.matcher(line);
                if (match.find()) out.put(Integer.parseInt(match.group(1)), match.group(2));
            }
        } finally {
            dumpsys.destroy();
        }
        // Our own process is always listed, so an empty result means the system refused the request.
        if (out.isEmpty()) throw new IOException(firstLine == null ? "inget svar från systemet" : firstLine);
        return out;
    }

    private static boolean isProtected(String pkg) {
        for (String prefix : PROTECTED) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix)) return true;
        }
        return false;
    }
}
