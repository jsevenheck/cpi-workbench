package io.cpiwb.core;

import java.util.Locale;
import java.util.TimeZone;

/**
 * A tenant's runtime defaults: timezone {@code Etc/UTC} and locale {@code en_US} (the fingerprint probes,
 * docs/plan.md F48). Every engine sets them at start, so dates, {@code current-dateTime()}, number formats
 * and case conversions do not depend on the computer. The tenant's charset (UTF-8) is Java's default from
 * Java 18 on; the app's Java runtime is 21.
 */
public final class TenantDefaults {

    public static final String TIMEZONE = "Etc/UTC";

    private TenantDefaults() {
    }

    public static synchronized void apply() {
        if (!TIMEZONE.equals(TimeZone.getDefault().getID())) {
            TimeZone.setDefault(TimeZone.getTimeZone(TIMEZONE));
        }
        if (!Locale.US.equals(Locale.getDefault())) {
            Locale.setDefault(Locale.US);
        }
    }
}
