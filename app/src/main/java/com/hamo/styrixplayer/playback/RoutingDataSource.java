package com.hamo.styrixplayer.playback;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Sends each request to one of two sources, chosen when the request is opened. */
final class RoutingDataSource implements DataSource {

    interface Chooser {
        boolean useCached(DataSpec spec);
    }

    private final DataSource plain;
    private final DataSource cached;
    private final Chooser chooser;
    @Nullable private DataSource active;

    RoutingDataSource(DataSource plain, DataSource cached, Chooser chooser) {
        this.plain = plain;
        this.cached = cached;
        this.chooser = chooser;
    }

    @Override
    public void addTransferListener(TransferListener l) {
        plain.addTransferListener(l);
        cached.addTransferListener(l);
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        active = chooser.useCached(spec) ? cached : plain;
        return active.open(spec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return active == null ? -1 : active.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return active == null ? null : active.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return active == null ? Collections.emptyMap() : active.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        DataSource a = active;
        active = null;
        if (a != null) a.close();
    }
}
