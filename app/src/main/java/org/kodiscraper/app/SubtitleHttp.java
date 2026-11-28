package org.kodiscraper.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;

final class SubtitleHttp {
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    private static final Map<Thread, HttpURLConnection> ACTIVE = new HashMap<>();

    private SubtitleHttp() {}

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static JSONObject json(String address, String header, String credential, String... domains) throws IOException {
        return jsonBody(get(address, header, credential, JSON_LIMIT, domains));
    }

    static JSONObject jsonFresh(String address, String header, String credential, String... domains)
            throws IOException {
        return jsonBody(getFresh(address, header, credential, JSON_LIMIT, domains));
    }

    private static JSONObject jsonBody(byte[] data) throws IOException {
        try {
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (JSONException invalid) {
            throw new IOException("字幕服务返回了无法读取的资料");
        }
    }

    static byte[] get(String address, String header, String credential, int limit, String... domains)
            throws IOException {
        return get(address, header, credential, limit, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, domains);
    }

    static byte[] get(String address, String header, String credential, int limit, int connectTimeoutMillis,
                      int readTimeoutMillis, String... domains) throws IOException {
        return request(address, header, credential, null, limit, connectTimeoutMillis, readTimeoutMillis,
                false, false, domains);
    }

    static byte[] getOk(String address, String header, String credential, int limit, String... domains)
            throws IOException {
        return request(address, header, credential, null, limit, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS,
                false, true, domains);
    }

    static byte[] getFresh(String address, String header, String credential, int limit, String... domains)
            throws IOException {
        return request(address, header, credential, null, limit, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS,
                true, false, domains);
    }

    static byte[] postForm(String address, String form, int limit, String... domains) throws IOException {
        return request(address, null, null, form.getBytes(StandardCharsets.UTF_8), limit,
                CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, false, false, domains);
    }

    static boolean isRetryableConnection(IOException failure) {
        return failure instanceof ConnectionException && !(failure.getCause() instanceof UnknownHostException);
    }

    static void cancel(Thread thread) {
        HttpURLConnection request;
        synchronized (ACTIVE) {
            request = ACTIVE.get(thread);
        }
        if (request != null) {
            request.disconnect();
        }
    }

    private static byte[] request(String address, String header, String credential, byte[] form, int limit,
                                  int connectTimeoutMillis, int readTimeoutMillis, boolean fresh,
                                  boolean requireOk, String[] domains) throws IOException {
        URI target = address(address);
        String origin = target.getHost();
        Thread worker = Thread.currentThread();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            ScrapeEngine.checkCancelled();
            if (!allowed(target, domains)) {
                throw new IOException("字幕下载地址超出来源允许范围");
            }
            HttpURLConnection connection = null;
            long started = System.nanoTime();
            int status = -1;
            try {
                connection = (HttpURLConnection) target.toURL().openConnection();
                synchronized (ACTIVE) {
                    ScrapeEngine.checkCancelled();
                    ACTIVE.put(worker, connection);
                }
                connection.setConnectTimeout(connectTimeoutMillis);
                connection.setReadTimeout(readTimeoutMillis);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "NFOForAndroid/1.17.0");
                if (fresh) {
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Connection", "close");
                    connection.setRequestProperty("Cache-Control", "no-cache");
                }
                if (header != null) {
                    connection.setRequestProperty(header, credential);
                }
                if (form != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(form.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) {
                        output.write(form);
                    }
                }
                int code = connection.getResponseCode();
                status = code;
                DebugLog.http(target.getHost(), code, started);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String redirect = connection.getHeaderField("Location");
                    if (redirect == null || form != null) {
                        throw new IOException("字幕服务返回了无效的跳转");
                    }
                    target = target.resolve(address(redirect));
                    // API credentials may be in the Authorization header or the query string.
                    if (!origin.equalsIgnoreCase(target.getHost()) && (header != null || address.contains("?"))) {
                        throw new IOException("字幕服务尝试跨站跳转");
                    }
                    continue;
                }
                if (code < 200 || code >= 300 || (requireOk && code != HttpURLConnection.HTTP_OK)) {
                    throw new HttpException(code, errorStatus(connection));
                }
                if (connection.getContentLengthLong() > limit) {
                    throw new IOException("字幕文件超过读取上限");
                }
                try (InputStream input = connection.getInputStream()) {
                    return read(input, limit);
                }
            } catch (SocketTimeoutException failure) {
                DebugLog.networkFailure(target.getHost(), status, failure);
                ScrapeEngine.checkCancelled();
                throw new ConnectionException("字幕连接超时", failure);
            } catch (UnknownHostException failure) {
                DebugLog.networkFailure(target.getHost(), status, failure);
                ScrapeEngine.checkCancelled();
                throw new ConnectionException("无法解析字幕服务器地址", failure);
            } catch (SocketException | EOFException failure) {
                DebugLog.networkFailure(target.getHost(), status, failure);
                ScrapeEngine.checkCancelled();
                throw new ConnectionException(failure instanceof ConnectException
                        ? "无法建立字幕连接" : "字幕连接被重置或提前断开", failure);
            } catch (SSLException failure) {
                DebugLog.networkFailure(target.getHost(), status, failure);
                ScrapeEngine.checkCancelled();
                if (DebugLog.unverifiedPeer(failure)) {
                    throw new IOException("字幕服务器 TLS 对端身份校验失败", failure);
                }
                if (fresh && tlsConnectionDropped(failure)) {
                    throw new ConnectionException("字幕 HTTPS 连接被重置或提前断开", failure);
                }
                throw new IOException("字幕服务器的 HTTPS 连接失败", failure);
            } catch (HttpException response) {
                throw response;
            } catch (IOException failure) {
                DebugLog.networkFailure(target.getHost(), status, failure);
                throw failure;
            } finally {
                synchronized (ACTIVE) {
                    ACTIVE.remove(worker);
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new IOException("字幕服务跳转次数过多");
    }

    private static boolean tlsConnectionDropped(SSLException failure) {
        boolean dropped = false;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof EOFException) dropped = true;
            if (cause instanceof SSLException && cause.getMessage() != null) {
                String message = cause.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("certificate") || message.contains("hostname")
                        || message.contains("trust anchor")) return false;
                dropped |= message.contains("connection reset") || message.contains("econnreset")
                        || message.contains("broken pipe") || message.contains("unexpected end of stream")
                        || message.contains("unexpected eof") || message.contains("unexpected_eof");
            }
        }
        return dropped;
    }

    private static URI address(String raw) throws IOException {
        try {
            return new URI(raw);
        } catch (URISyntaxException | NullPointerException failure) {
            throw new IOException("字幕服务返回了无效的网址");
        }
    }

    private static boolean allowed(URI url, String[] domains) {
        String host = url.getHost();
        if (!"https".equalsIgnoreCase(url.getScheme()) || host == null || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private static int errorStatus(HttpURLConnection connection) {
        try (InputStream input = connection.getErrorStream()) {
            return input == null ? -1
                    : new JSONObject(new String(read(input, 8192), StandardCharsets.UTF_8)).optInt("status", -1);
        } catch (IOException | JSONException ignored) {
            return -1;
        }
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            ScrapeEngine.checkCancelled();
            if (count > limit - data.size()) {
                throw new IOException("字幕文件超过读取上限");
            }
            data.write(buffer, 0, count);
        }
        return data.toByteArray();
    }

    static final class HttpException extends IOException {
        final int statusCode;
        final int apiStatus;

        HttpException(int statusCode, int apiStatus) {
            super("字幕服务返回 HTTP " + statusCode);
            this.statusCode = statusCode;
            this.apiStatus = apiStatus;
        }
    }

    static final class ConnectionException extends IOException {
        ConnectionException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
