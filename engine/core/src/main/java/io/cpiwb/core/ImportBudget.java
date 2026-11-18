package io.cpiwb.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** One budget for an import, shared by every artifact in a package. */
final class ImportBudget {
    static final long MAX_BYTES = 200L * 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;

    private long bytesLeft;
    private int entriesLeft;
    private final long maxBytes;
    private final int maxEntries;

    ImportBudget(long bytes, int entries) {
        bytesLeft = maxBytes = bytes;
        entriesLeft = maxEntries = entries;
    }

    ImportBudget() {
        this(MAX_BYTES, MAX_ENTRIES);
    }

    void entries(int count) throws IOException {
        entriesLeft -= count;
        if (entriesLeft < 0) {
            throw new IOException("UnsupportedInSimulator: the import exceeds " + maxEntries + " ZIP entries.");
        }
    }

    InputStream bounded(InputStream input) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                int b = in.read();
                if (b >= 0) charge(1);
                return b;
            }

            @Override public int read(byte[] dest, int offset, int length) throws IOException {
                int n = in.read(dest, offset, (int) Math.min(length, bytesLeft + 1));
                if (n > 0) charge(n);
                return n;
            }
        };
    }

    private void charge(int n) throws IOException {
        bytesLeft -= n;
        if (bytesLeft < 0) {
            throw new IOException("UnsupportedInSimulator: the import exceeds " + maxBytes + " bytes unpacked.");
        }
    }
}
