package net.dongliu.proxy.data;

import net.dongliu.proxy.utils.Texts;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Http request headers
 */
public class Http2RequestHeaders extends Http2Headers implements HttpRequestHeaders, Serializable {
    private static final long serialVersionUID = -4334647855383940843L;
    private final String scheme;
    private final String method;
    private final String path;
    // only for connect
    private final String authority;

    public Http2RequestHeaders(List<Header> headers, String scheme, String method, String path) {
        this(headers, scheme, method, path, "");
    }

    public Http2RequestHeaders(List<Header> headers, String scheme, String method, String path, String authority) {
        super(headers);
        this.scheme = scheme;
        this.method = method;
        this.path = path;
        this.authority = authority;
    }

    public String scheme() {
        return scheme;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String version() {
        return "2.0";
    }

    @Override
    public List<String> rawLines() {
        List<Header> allHeaders = new ArrayList<>(1 + headers().size());
        allHeaders.add(new Header(":scheme", scheme));
        allHeaders.add(new Header(":method", method));
        allHeaders.add(new Header(":path", path));
        allHeaders.addAll(headers());
        return Texts.toAlignText(allHeaders, ": ");
    }

    @Override
    public List<NameValue> cookieValues() {
        return getHeader("Cookie").stream().flatMap(v -> Stream.of(v.split(";")))
                .map(String::trim)
                .map(Parameter::parse)
                .collect(toList());
    }

    @Override
    public String toString() {
        return "Http2RequestHeaders{" +
                "scheme='" + scheme + '\'' +
                ", method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", authority='" + authority + '\'' +
                '}';
    }
}
