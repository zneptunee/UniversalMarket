package com.sola.universalmarket.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * THE central money formatter. Every single balance, price, fee and reward in
 * UniversalMarket goes through this class. Nothing else in the plugin is allowed
 * to call String.format on a currency value.
 *
 * Rules (from spec section 5):
 *   below 100,000  -> full number, comma separated, no cents      $0  $54  $9,500  $99,999
 *   100,000 and up -> abbreviated, at most 2 decimals, truncated  $100K  $145.5K  $999.99K
 *                                                                 $1M  $18.42M  $100M  $500M
 *                                                                 $1B  $500B  $1T  $500T  $1Q
 *   never show cents, never show trailing zeros ($18.50M -> $18.5M, $100.00M -> $100M)
 *
 * Truncation (not rounding) is deliberate: 999,999 must read as $999.99K, because
 * rounding would produce the nonsense "$1000K". It also means an abbreviated
 * balance never overstates what a player can actually afford.
 */
public final class NumberFormatter {

    private NumberFormatter() {}

    /**
     * Below this, print the exact number with separators.
     *
     * Spec section 5 says the cutover is 100,000, which makes 70,000 render as
     * "$70,000". Spec section 20 writes that same value as "$70K" in prose.
     * Those two rules disagree, so the cutover is config-driven
     * (number-format.abbreviate-at). Default 100000 follows section 5, the
     * explicit rule. Set it to 10000 if you prefer the section 20 look.
     */
    private static volatile BigDecimal abbrevThreshold = BigDecimal.valueOf(100_000L);

    public static void setAbbreviateAt(long value) {
        abbrevThreshold = BigDecimal.valueOf(Math.max(1L, value));
    }

    public static BigDecimal abbreviateAt() {
        return abbrevThreshold;
    }

    private static final BigDecimal K = new BigDecimal("1000");
    private static final BigDecimal M = new BigDecimal("1000000");
    private static final BigDecimal B = new BigDecimal("1000000000");
    private static final BigDecimal T = new BigDecimal("1000000000000");
    private static final BigDecimal Q = new BigDecimal("1000000000000000");

    private static final ThreadLocal<DecimalFormat> GROUPED = ThreadLocal.withInitial(() -> {
        DecimalFormat df = new DecimalFormat("#,##0", new DecimalFormatSymbols(Locale.US));
        df.setGroupingUsed(true);
        return df;
    });

    /** Currency symbol; overridden from config at startup. */
    private static volatile String symbol = "$";

    public static void setSymbol(String s) {
        symbol = (s == null || s.isEmpty()) ? "$" : s;
    }

    public static String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------------
    // Primary entry points
    // ------------------------------------------------------------------

    /** Format a money value, e.g. 18420000 -> "$18.42M". */
    public static String money(BigDecimal amount) {
        if (amount == null) return symbol + "0";
        BigDecimal whole = amount.setScale(0, RoundingMode.DOWN);
        boolean negative = whole.signum() < 0;
        String body = abbreviate(whole.abs());
        return (negative ? "-" + symbol : symbol) + body;
    }

    public static String money(long amount) {
        return money(BigDecimal.valueOf(amount));
    }

    public static String money(double amount) {
        return money(BigDecimal.valueOf(amount));
    }

    /** Format without the currency symbol (for use inside sentences / config). */
    public static String plain(BigDecimal amount) {
        if (amount == null) return "0";
        BigDecimal whole = amount.setScale(0, RoundingMode.DOWN);
        return (whole.signum() < 0 ? "-" : "") + abbreviate(whole.abs());
    }

    /** Always the exact, fully written out number with separators: 18420000 -> "18,420,000". */
    public static String exact(BigDecimal amount) {
        if (amount == null) return "0";
        return GROUPED.get().format(amount.setScale(0, RoundingMode.DOWN).toBigInteger());
    }

    /** Exact value with symbol, for confirmation screens where precision matters. */
    public static String exactMoney(BigDecimal amount) {
        if (amount == null) return symbol + "0";
        BigDecimal whole = amount.setScale(0, RoundingMode.DOWN);
        boolean neg = whole.signum() < 0;
        return (neg ? "-" + symbol : symbol) + GROUPED.get().format(whole.abs().toBigInteger());
    }

    // ------------------------------------------------------------------
    // Core abbreviation
    // ------------------------------------------------------------------

    private static String abbreviate(BigDecimal abs) {
        if (abs.compareTo(abbrevThreshold) < 0) {
            return GROUPED.get().format(abs.toBigInteger());
        }
        BigDecimal divisor;
        String suffix;
        if (abs.compareTo(Q) >= 0)      { divisor = Q; suffix = "Q"; }
        else if (abs.compareTo(T) >= 0) { divisor = T; suffix = "T"; }
        else if (abs.compareTo(B) >= 0) { divisor = B; suffix = "B"; }
        else if (abs.compareTo(M) >= 0) { divisor = M; suffix = "M"; }
        else                            { divisor = K; suffix = "K"; }

        // Truncate toward zero at 2dp, then drop trailing zeros: 18.50 -> 18.5, 100.00 -> 100
        BigDecimal scaled = abs.divide(divisor, 2, RoundingMode.DOWN).stripTrailingZeros();
        if (scaled.scale() < 0) scaled = scaled.setScale(0);
        return scaled.toPlainString() + suffix;
    }

    // ------------------------------------------------------------------
    // Parsing (for /um pay 10m, custom amount entry, config values)
    // ------------------------------------------------------------------

    /**
     * Parse user input like "1000", "9,500", "$10m", "1.5b", "500T".
     * Returns null when the input is not a clean, finite, non-negative amount.
     * Rejects NaN / Infinity / negatives / garbage rather than throwing.
     */
    public static BigDecimal parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replace(",", "").replace("_", "").replace(" ", "");
        if (s.startsWith("$")) s = s.substring(1);
        if (s.isEmpty()) return null;

        BigDecimal mult = BigDecimal.ONE;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = K;
            case 'm' -> mult = M;
            case 'b' -> mult = B;
            case 't' -> mult = T;
            case 'q' -> mult = Q;
            default  -> { /* no suffix */ }
        }
        if (!mult.equals(BigDecimal.ONE)) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return null;

        BigDecimal value;
        try {
            value = new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (value.signum() < 0) return null;
        BigDecimal result = value.multiply(mult).setScale(0, RoundingMode.DOWN);
        // Sanity ceiling: refuse absurd values that would break the economy or overflow.
        if (result.compareTo(MAX_TRANSACTION) > 0) return null;
        return result;
    }

    /** Hard ceiling on any single transaction: 1 quintillion. Guards against overflow abuse. */
    public static final BigDecimal MAX_TRANSACTION =
            new BigDecimal(new BigInteger("1000000000000000000"));

    // ------------------------------------------------------------------
    // Misc helpers
    // ------------------------------------------------------------------

    /** 0.2 -> "20%", 0.425 -> "42.5%" */
    public static String percent(double fraction) {
        BigDecimal p = BigDecimal.valueOf(fraction * 100d).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
        if (p.scale() < 0) p = p.setScale(0);
        return p.toPlainString() + "%";
    }

    /** Format a plain count like 1,234 (items, not money). */
    public static String count(long n) {
        return GROUPED.get().format(n);
    }

    /** "1h 22m", "3d 4h", "45s" - used for cooldowns and cycle timers. */
    public static String duration(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000L;
        long d = s / 86400L; s %= 86400L;
        long h = s / 3600L;  s %= 3600L;
        long m = s / 60L;    s %= 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 && d == 0) sb.append(m).append("m ");
        if (sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }

    /** Whole-dollar rounding used before any Vault call. Always rounds down (never overcharges). */
    public static BigDecimal toWholeDollars(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(0, RoundingMode.DOWN);
    }

    /** Guard against NaN / Infinity coming back from a third party economy plugin. */
    public static BigDecimal safe(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(d).setScale(0, RoundingMode.DOWN);
    }
}
