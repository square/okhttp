package com.squareup.okhttp;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class UrlBuilder {
    private String url;
    private StringBuilder queryParams;

    public UrlBuilder(String url) {
        this.url = url;
    }

    public UrlBuilder addPathParam(String name, String value) {
        return addPathParam(name, value, true);
    }

    public UrlBuilder addEncodedPathParam(String name, String value) {
        return addPathParam(name, value, false);
    }

    private UrlBuilder addPathParam(String name, String value, boolean urlEncodeValue) {
        if (name == null) {
            throw new IllegalArgumentException("Path replacement name must not be null.");
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "Path replacement \"" + name + "\" value must not be null.");
        }
        try {
            if (urlEncodeValue) {
                String encodedValue = URLEncoder.encode(value, "UTF-8");
                // URLEncoder encodes for use as a query parameter. Path encoding uses %20 to
                // encode spaces rather than +. Query encoding difference specified in HTML spec.
                // Any remaining plus signs represent spaces as already URLEncoded.
                encodedValue = encodedValue.replace("+", "%20");
                url = url.replace("{" + name + "}", encodedValue);
            } else {
                url = url.replace("{" + name + "}", value);
            }
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(
                    "Unable to convert path parameter \"" + name + "\" value to UTF-8:" + value, e);
        }

        return this;
    }

    public UrlBuilder addQueryParam(String name, String value) {
        return addQueryParam(name, value, true);
    }

    public UrlBuilder addEncodedQueryParam(String name, String value) {
        return addQueryParam(name, value, false);
    }

    public UrlBuilder addQueryParam(String name, String value, boolean urlEncodeValue) {
        if (name == null) {
            throw new IllegalArgumentException("Query param name must not be null.");
        }
        if (value == null) {
            throw new IllegalArgumentException("Query param \""
                    + name + "\" value must not be null.");
        }
        try {
            if (urlEncodeValue) {
                value = URLEncoder.encode(value, "UTF-8");
            }
            StringBuilder queryParams = this.queryParams;
            if (queryParams == null) {
                this.queryParams = queryParams = new StringBuilder();
            }

            queryParams.append(queryParams.length() > 0 ? '&' : '?');
            queryParams.append(name).append('=').append(value);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(
                    "Unable to convert query parameter \""
                            + name + "\" value to UTF-8: " + value, e);
        }

        return this;
    }

    public String build() {
        if (queryParams != null) {
            url = url + queryParams.toString();
        }

        return url;
    }
}
