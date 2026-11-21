package com.gathin.tvhome;

import android.net.Uri;
import android.os.CancellationSignal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import javax.net.ssl.HttpsURLConnection;

/** HTTPS artwork uses the Sony build's existing network-enabled source set. */
final class SuggestionArtworkNetwork {
    static byte[] read(Uri uri, CancellationSignal cancel) throws IOException {
        URI url = URI.create(uri.toString());
        for (int redirects = 0; redirects <= 2 && !cancel.isCanceled(); redirects++) {
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                    || url.getUserInfo() != null) return null;
            HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
            try {
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    url = url.resolve(location);
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > SuggestionArtwork.MAX_IMAGE_BYTES) return null;
                try (InputStream stream = connection.getInputStream()) {
                    return SuggestionArtwork.readBytes(stream, cancel);
                }
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }
}
