package com.hamo.styrixplayer.yt;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** The HTTP layer NewPipeExtractor needs, written on HttpURLConnection so no OkHttp is required. */
public final class NpDownloader extends Downloader {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        try {
            c.setRequestMethod(request.httpMethod());
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", UA);
            for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
                boolean first = true;
                for (String v : e.getValue()) {
                    if (first) c.setRequestProperty(e.getKey(), v);
                    else c.addRequestProperty(e.getKey(), v);
                    first = false;
                }
            }
            byte[] body = request.dataToSend();
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }

            int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("YouTube asked for a captcha", request.url());

            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                try (InputStream is = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    text = bo.toString("UTF-8");
                }
            }

            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) headers.put(e.getKey(), e.getValue());
            }
            return new Response(code, c.getResponseMessage(), headers, text, c.getURL().toString());
        } finally {
            c.disconnect();
        }
    }
}
