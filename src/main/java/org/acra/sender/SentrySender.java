package org.acra.sender;

//Based on raven-java(Ken Cochrane and others)

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.acra.ACRA;
import org.acra.ReportField;
import org.acra.collector.CrashReportData;
import org.acra.sender.HttpSender.Method;
import org.acra.util.HttpRequest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import static org.acra.ReportField.*;

public class SentrySender implements ReportSender {

    public static final ReportField[] SENTRY_TAGS_FIELDS = {APP_VERSION_CODE, APP_VERSION_NAME, PACKAGE_NAME, FILE_PATH, PHONE_MODEL,
            BRAND, PRODUCT, ANDROID_VERSION, TOTAL_MEM_SIZE, AVAILABLE_MEM_SIZE, IS_SILENT, INSTALLATION_ID, DISPLAY};
    public static ReportField[] SENTRY_EXTRA_FIELDS = {USER_APP_START_DATE, USER_CRASH_DATE, USER_IP, DEVICE_FEATURES,
            SETTINGS_SECURE, CRASH_CONFIGURATION, BUILD, ENVIRONMENT, CUSTOM_DATA};

    private SentryConfig config;

    /**
     * Takes in a sentryDSN
     *
     * @param sentryDSN '{PROTOCOL}://{PUBLIC_KEY}:{SECRET_KEY}@{HOST}/{PATH}/{PROJECT_ID}
     *                  '
     */
    public SentrySender(String sentryDSN) {
        if (sentryDSN == null) {
            return;
        }
        config = new SentryConfig(sentryDSN);
    }

    public SentrySender() {
        if (ACRA.getConfig().formUri() == null) {
            return;
        }
        config = new SentryConfig(ACRA.getConfig().formUri());
    }

    @Override
    public void send(CrashReportData errorContent) throws ReportSenderException {

        if (config == null) {
            return;
        }

        final HttpRequest request = new HttpRequest();
        request.setConnectionTimeOut(ACRA.getConfig().connectionTimeout());
        request.setSocketTimeOut(ACRA.getConfig().socketTimeout());
        request.setMaxNrRetries(ACRA.getConfig().maxNumberOfRequestRetries());

        Map<String, String> extra_headers = new HashMap<String, String>();
        extra_headers.put("X-Sentry-Auth", buildAuthHeader());
        request.setHeaders(extra_headers);
        try {
            request.send(config.getSentryURL(), Method.POST, buildJSON(errorContent), org.acra.sender.HttpSender.Type.JSON);
        } catch (MalformedURLException e) {
            throw new ReportSenderException("Error while sending report to Sentry.", e);
        } catch (IOException e) {
            throw new ReportSenderException("Error while sending report to Sentry.", e);
        } catch (JSONException e) {
            throw new ReportSenderException("Error while sending report to Sentry.", e);
        }
    }

    /**
     * Build up the sentry auth header in the following format.
     * <p/>
     * The header is composed of the timestamp from when the message was
     * generated, and an arbitrary client version string. The client version
     * should be something distinct to your client, and is simply for reporting
     * purposes.
     * <p/>
     * X-Sentry-Auth: Sentry sentry_version=3, sentry_timestamp=<signature
     * timestamp>[, sentry_key=<public api key>,[ sentry_client=<client version,
     * arbitrary>]]
     *
     * @param hmacSignature SHA1-signed HMAC
     * @param publicKey     is either the public_key or the shared global key between
     *                      client and server.
     * @return String version of the sentry auth header
     */
    protected String buildAuthHeader() {
        /*
		 * X-Sentry-Auth: Sentry sentry_version=3, sentry_client=<client
		 * version, arbitrary>, sentry_timestamp=<current timestamp>,
		 * sentry_key=<public api key>, sentry_secret=<secret api key>
		 */
        StringBuilder header = new StringBuilder();
        header.append("Sentry sentry_version=4");
        header.append(",sentry_client=ACRA/5.0.0");
        header.append(",sentry_key=").append(config.getPublicKey());
        header.append(",sentry_secret=").append(config.getSecretKey());
        return header.toString();
    }

    /**
     * Given the time right now return a ISO8601 formatted date string
     *
     * @return ISO8601 formatted date string
     */
    public String getTimestampString() {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        df.setTimeZone(tz);
        return df.format(new Date());
    }

    private String buildJSON(CrashReportData report) throws JSONException {
        JSONObject obj = new JSONObject();
        Throwable exception = report.getOriginalThrowable();
        String message = report.getProperty(ReportField.STACK_TRACE).split("\n")[0];
        obj.put("event_id", report.getProperty(ReportField.REPORT_ID).replace("-", "")); // Hexadecimal
        // string
        // representing
        // a
        // uuid4
        // value.

        if (exception == null) {
            obj.put("culprit", message);
        } else {
            obj.put("culprit", determineCulprit(exception));
            obj.put("sentry.interfaces.Exception", buildException(exception));
            obj.put("sentry.interfaces.Stacktrace", buildStacktrace(exception));
        }

        obj.put("level", "error");
        obj.put("timestamp", getTimestampString());
        if (exception != null) {
            obj.put("message", exception.getMessage());
        } else {
            obj.put("message", message);
        }
        obj.put("logger", "org.acra");
        obj.put("platform", "android");
        obj.put("tags", remap(report, SENTRY_TAGS_FIELDS));

        JSONObject extraFields = remap(report, SENTRY_EXTRA_FIELDS);

        if (ACRA.getConfig().customReportContent().length > 0) {
            extraFields = remap(report, ACRA.getConfig().customReportContent(), extraFields);
        }

        obj.put("extra", extraFields);

        if (ACRA.DEV_LOGGING) {
            ACRA.log.d(ACRA.LOG_TAG, obj.toString());
        }

        return obj.toString();
    }

    /**
     * Determines the class and method name where the root cause exception
     * occurred.
     *
     * @param exception exception
     * @return the culprit
     */
    private String determineCulprit(Throwable exception) {
        Throwable cause = exception;
        String culprit = null;
        while (cause != null) {
            StackTraceElement[] elements = cause.getStackTrace();
            if (elements.length > 0) {
                StackTraceElement trace = elements[0];
                culprit = trace.getClassName() + "." + trace.getMethodName();
            }
            cause = cause.getCause();
        }
        return culprit;
    }

    private JSONObject buildException(Throwable exception) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", exception.getClass().getSimpleName());
        json.put("value", exception.getMessage());
        json.put("module", exception.getClass().getPackage().getName());
        return json;
    }

    private JSONObject buildStacktrace(Throwable exception) throws JSONException {
        JSONArray array = new JSONArray();
        Throwable cause = exception;
        while (cause != null) {
            StackTraceElement[] elements = cause.getStackTrace();
            for (int index = 0; index < elements.length; ++index) {
                if (index == 0) {
                    JSONObject causedByFrame = new JSONObject();
                    String msg = "Caused by: " + cause.getClass().getName();
                    if (cause.getMessage() != null) {
                        msg += " (\"" + cause.getMessage() + "\")";
                    }
                    causedByFrame.put("filename", msg);
                    causedByFrame.put("lineno", -1);
                    array.put(causedByFrame);
                }
                StackTraceElement element = elements[index];
                JSONObject frame = new JSONObject();
                frame.put("filename", element.getClassName());
                frame.put("function", element.getMethodName());
                frame.put("lineno", element.getLineNumber());
                array.put(frame);
            }
            cause = cause.getCause();
        }
        JSONObject stacktrace = new JSONObject();
        stacktrace.put("frames", array);
        return stacktrace;
    }

    private JSONObject remap(CrashReportData report, ReportField[] fields) throws JSONException {
        return remap(report, fields, null);
    }

    private JSONObject remap(CrashReportData report, ReportField[] fields, JSONObject extend) throws JSONException {
        final JSONObject result;

        if (extend == null)
            result = new JSONObject();
        else
            result = extend;

        for (ReportField originalKey : fields) {
            result.put(originalKey.toString(), report.getProperty(originalKey));

            if (ACRA.DEV_LOGGING)
                ACRA.log.d(ACRA.LOG_TAG, originalKey.toString() + ": " + report.getProperty(originalKey));
        }
        return result;
    }

    private class SentryConfig {

        private String host, protocol, publicKey, secretKey, path, projectId;
        private int port;

        /**
         * Takes in a sentryDSN and builds up the configuration
         *
         * @param sentryDSN '{PROTOCOL}://{PUBLIC_KEY}:{SECRET_KEY}@{HOST}/{PATH}/{PROJECT_ID}
         *                  '
         */
        public SentryConfig(String sentryDSN) {

            try {
                URL url = new URL(sentryDSN);
                this.host = url.getHost();
                this.protocol = url.getProtocol();
                String urlPath = url.getPath();

                int lastSlash = urlPath.lastIndexOf("/");
                this.path = urlPath.substring(0, lastSlash);
                // ProjectId is the integer after the last slash in the path
                this.projectId = urlPath.substring(lastSlash + 1);

                String userInfo = url.getUserInfo();
                String[] userParts = userInfo.split(":");

                this.secretKey = userParts[1];
                this.publicKey = userParts[0];

                this.port = url.getPort();

            } catch (MalformedURLException e) {
                e.printStackTrace();
            }

        }

        /**
         * The Sentry server URL that we post the message to.
         *
         * @return sentry server url
         * @throws MalformedURLException
         */
        public URL getSentryURL() throws MalformedURLException {
            StringBuilder serverUrl = new StringBuilder();
            serverUrl.append(getProtocol());
            serverUrl.append("://");
            serverUrl.append(getHost());
            if ((getPort() != 0) && (getPort() != 80) && getPort() != -1) {
                serverUrl.append(":").append(getPort());
            }
            serverUrl.append(getPath());
            serverUrl.append("/api/" + getProjectId() + "/store/");
            return new URL(serverUrl.toString());
        }

        /**
         * The sentry server host
         *
         * @return server host
         */
        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        /**
         * Sentry server protocol http https?
         *
         * @return http or https
         */
        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        /**
         * The Sentry public key
         *
         * @return Sentry public key
         */
        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        /**
         * The Sentry secret key
         *
         * @return Sentry secret key
         */
        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        /**
         * sentry url path
         *
         * @return url path
         */
        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        /**
         * Sentry project Id
         *
         * @return project Id
         */
        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        /**
         * sentry server port
         *
         * @return server port
         */
        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

    }
}
