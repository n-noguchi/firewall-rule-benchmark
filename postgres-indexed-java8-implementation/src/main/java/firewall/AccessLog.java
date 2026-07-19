package firewall;

public final class AccessLog {
    public String accessId;
    public String sourceIpv4;
    public String urlPath;
    public String accessTimestampUtc;
    public String referer;
    public String userAgent;

    public AccessLog() {
    }

    public AccessLog(String accessId, String sourceIpv4, String urlPath, String accessTimestampUtc, String referer, String userAgent) {
        this.accessId = accessId;
        this.sourceIpv4 = sourceIpv4;
        this.urlPath = urlPath;
        this.accessTimestampUtc = accessTimestampUtc;
        this.referer = referer;
        this.userAgent = userAgent;
    }
}
