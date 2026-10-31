package se.makeover.home;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder: byte mode, error correction level M, versions 1–6 (up to 106 bytes),
 * which is plenty for a short URL. Follows the structure of Project Nayuki's reference encoder.
 */
final class QrCode {
    // Level M, indexed by version 1..6: data codewords per block, blocks, EC codewords per block.
    private static final int[] DATA_PER_BLOCK = {0, 16, 28, 44, 32, 43, 27};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int FORMAT_LEVEL_M = 0;
    private static final int QUIET_ZONE = 4;

    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    /** Renders text as a black-on-white QR code with a quiet zone; each module is scale x scale pixels. */
    static Bitmap render(String text, int scale) {
        QrCode qr = encode(text.getBytes(StandardCharsets.UTF_8));
        int px = (qr.size + QUIET_ZONE * 2) * scale;
        int[] pixels = new int[px * px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int mx = x / scale - QUIET_ZONE;
                int my = y / scale - QUIET_ZONE;
                boolean dark = mx >= 0 && my >= 0 && mx < qr.size && my < qr.size && qr.modules[my][mx];
                pixels[y * px + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888);
    }

    private static QrCode encode(byte[] data) {
        int version = 1;
        while (data.length * 8 + 12 > DATA_PER_BLOCK[version] * BLOCKS[version] * 8) {
            if (++version > 6) throw new IllegalArgumentException("Text too long for this QR encoder");
        }
        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns(version);
        qr.drawCodewords(addErrorCorrection(dataCodewords(data, version), version));

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            qr.applyMask(mask);
            qr.drawFormatBits(mask);
            int penalty = qr.penalty();
            if (penalty < bestPenalty) {
                bestMask = mask;
                bestPenalty = penalty;
            }
            qr.applyMask(mask); // XOR again to undo
        }
        qr.applyMask(bestMask);
        qr.drawFormatBits(bestMask);
        return qr;
    }

    // --- Data ---

    /** Mode indicator, length, data, terminator and pad bytes, filling the version's data capacity. */
    private static byte[] dataCodewords(byte[] data, int version) {
        int capacity = DATA_PER_BLOCK[version] * BLOCKS[version];
        boolean[] bits = new boolean[capacity * 8];
        int n = append(bits, 0, 0b0100, 4);
        n = append(bits, n, data.length, 8);
        for (byte b : data) n = append(bits, n, b & 0xFF, 8);
        n += Math.min(4, bits.length - n);
        n = (n + 7) / 8 * 8;

        byte[] out = new byte[capacity];
        for (int i = 0; i < n; i++) {
            if (bits[i]) out[i >>> 3] |= 1 << (7 - (i & 7));
        }
        for (int i = n / 8, pad = 0xEC; i < capacity; i++, pad ^= 0xEC ^ 0x11) out[i] = (byte) pad;
        return out;
    }

    private static int append(boolean[] bits, int n, int value, int length) {
        for (int i = length - 1; i >= 0; i--) bits[n++] = ((value >>> i) & 1) != 0;
        return n;
    }

    /** Splits data into blocks, adds Reed-Solomon codewords and interleaves the result. */
    private static byte[] addErrorCorrection(byte[] data, int version) {
        int blocks = BLOCKS[version];
        int dataLen = DATA_PER_BLOCK[version];
        int ecLen = EC_PER_BLOCK[version];
        byte[] divisor = reedSolomonDivisor(ecLen);
        byte[][] ec = new byte[blocks][];
        for (int b = 0; b < blocks; b++) ec[b] = reedSolomonRemainder(data, b * dataLen, dataLen, divisor);

        byte[] out = new byte[blocks * (dataLen + ecLen)];
        int k = 0;
        for (int i = 0; i < dataLen; i++) {
            for (int b = 0; b < blocks; b++) out[k++] = data[b * dataLen + i];
        }
        for (int i = 0; i < ecLen; i++) {
            for (int b = 0; b < blocks; b++) out[k++] = ec[b][i];
        }
        return out;
    }

    private static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonRemainder(byte[] data, int offset, int length, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (int i = offset; i < offset + length; i++) {
            int factor = (data[i] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j++) result[j] ^= (byte) multiply(divisor[j] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // --- Matrix ---

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        if (version >= 2) drawAlignment(size - 7, size - 7);
        drawFormatBits(0); // reserves the format areas; overwritten once the mask is chosen
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_LEVEL_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true); // the always-dark module
    }

    /** Places the codeword bits in the zigzag order, two columns at a time from the bottom right. */
    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // skip the vertical timing pattern
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= invert;
            }
        }
    }

    /** Standard mask penalty: long runs, 2x2 blocks, finder-like patterns and dark/light balance. */
    private int penalty() {
        int result = 0;
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean c = modules[y][x];
                if (c) dark++;
                if (x < size - 1 && y < size - 1
                    && c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    result += 3;
                }
            }
        }
        for (int i = 0; i < size; i++) result += linePenalty(i, true) + linePenalty(i, false);
        int total = size * size;
        result += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10;
        return result;
    }

    private int linePenalty(int index, boolean row) {
        int result = 0;
        int run = 0;
        boolean last = false;
        int window = 0;
        for (int i = 0; i < size; i++) {
            boolean c = row ? modules[index][i] : modules[i][index];
            if (i > 0 && c == last) {
                run++;
                if (run == 5) result += 3;
                else if (run > 5) result++;
            } else {
                run = 1;
            }
            last = c;
            window = ((window << 1) | (c ? 1 : 0)) & 0x7FF;
            if (i >= 10 && (window == 0x5D0 || window == 0x05D)) result += 40;
        }
        return result;
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }
}
