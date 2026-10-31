package se.makeover.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Background images in the app's own storage folder, pushed from the PC or uploaded from a phone. */
final class Backgrounds {
    private static final String[] TYPES = {".jpg", ".jpeg", ".png", ".webp"};
    private static final int UPLOAD_WIDTH = 1920;
    private static final int UPLOAD_HEIGHT = 1080;
    private static final Pattern AUTO_NAME = Pattern.compile("Bild (\\d+)\\.jpg");

    private Backgrounds() {}

    /** All images, sorted so that "Bild 2" comes before "Bild 10". */
    static List<File> list(Context context) {
        File dir = dir(context);
        File[] files = dir == null ? null : dir.listFiles(f -> f.isFile() && isImage(f.getName()));
        List<File> out = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        out.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        return out;
    }

    /** Decodes an image at roughly the size it is shown at when fitted inside width x height. */
    static Bitmap decode(File file, int width, int height) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        float needed = Math.min((float) width / bounds.outWidth, (float) height / bounds.outHeight);
        int sample = 1;
        while (needed * sample * 2 <= 1f) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) return null;

        float scale = Math.min((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        if (scale < 0.75f) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        return bitmap;
    }

    /**
     * Turns an uploaded photo into an upright JPEG named "Bild N", just big enough to cover the TV
     * screen. Returns null if the image cannot be decoded.
     */
    static File saveUpload(Context context, File source) throws IOException {
        File dir = dir(context);
        if (dir == null) throw new IOException("No storage");

        int rotation = rotationOf(source);
        boolean sideways = rotation == 90 || rotation == 270;
        Bitmap bitmap = sideways
            ? decodeSampled(source, UPLOAD_HEIGHT, UPLOAD_WIDTH)
            : decodeSampled(source, UPLOAD_WIDTH, UPLOAD_HEIGHT);
        if (bitmap == null) return null;

        int uprightW = sideways ? bitmap.getHeight() : bitmap.getWidth();
        int uprightH = sideways ? bitmap.getWidth() : bitmap.getHeight();
        float scale = Math.min(1f, Math.max((float) UPLOAD_WIDTH / uprightW, (float) UPLOAD_HEIGHT / uprightH));
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        matrix.postScale(scale, scale);
        Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (upright != bitmap) bitmap.recycle();

        File target = reserveAutoName(dir);
        try (FileOutputStream out = new FileOutputStream(target)) {
            upright.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            target.delete();
            throw e;
        } finally {
            upright.recycle();
        }
        return target;
    }

    /** Renames an image, keeping its extension. Returns the new file, or null on failure. */
    static File rename(File file, String newName) {
        String clean = cleanName(newName);
        if (clean.equals(displayName(file))) return file;
        String name = file.getName();
        String extension = name.substring(name.lastIndexOf('.'));
        File target = uniqueFile(file.getParentFile(), clean, extension);
        return file.renameTo(target) ? target : null;
    }

    static String displayName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) return null;
        File dir = new File(base, "backgrounds");
        dir.mkdirs();
        return dir;
    }

    /** Decodes with the largest power-of-two sample size that still covers width x height. */
    private static Bitmap decodeSampled(File file, int width, int height) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Phone cameras store portrait photos sideways and record the rotation in EXIF. */
    private static int rotationOf(File file) {
        try {
            int orientation = new ExifInterface(file.getPath())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /** Creates the next free "Bild N.jpg" so parallel uploads never get the same name. */
    private static synchronized File reserveAutoName(File dir) throws IOException {
        int highest = 0;
        String[] names = dir.list();
        if (names != null) {
            for (String name : names) {
                Matcher match = AUTO_NAME.matcher(name);
                if (match.matches()) highest = Math.max(highest, Integer.parseInt(match.group(1)));
            }
        }
        for (int n = highest + 1; ; n++) {
            File file = new File(dir, "Bild " + n + ".jpg");
            if (file.createNewFile()) return file;
        }
    }

    private static String cleanName(String name) {
        String clean = name.replaceAll("[^\\p{L}\\p{N} _-]", "_").trim();
        if (clean.length() > 40) clean = clean.substring(0, 40).trim();
        return clean.isEmpty() ? "Bild" : clean;
    }

    private static synchronized File uniqueFile(File dir, String base, String extension) {
        File file = new File(dir, base + extension);
        for (int i = 2; file.exists(); i++) file = new File(dir, base + " (" + i + ")" + extension);
        return file;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String type : TYPES) {
            if (lower.endsWith(type)) return true;
        }
        return false;
    }

    /** Case-insensitive comparison that orders digit runs by value. */
    private static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String numA = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String numB = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                if (numA.length() != numB.length()) return numA.length() - numB.length();
                int byValue = numA.compareTo(numB);
                if (byValue != 0) return byValue;
            } else {
                int byChar = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (byChar != 0) return byChar;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
