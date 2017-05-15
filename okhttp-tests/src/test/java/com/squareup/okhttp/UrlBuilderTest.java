package com.squareup.okhttp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UrlBuilderTest {
    @Test
    public void urlWithoutQuery() throws Exception {
        String url = new UrlBuilder("http://foo.com/")
                .build();

        assertEquals("http://foo.com/", url);
    }

    @Test
    public void addPathParams() throws Exception {
        String url = new UrlBuilder("http://foo.com/{first_name}/{last_name}")
                .addPathParam("first_name", "John")
                .addPathParam("last_name", "Doe")
                .build();

        assertEquals("http://foo.com/John/Doe", url);
    }

    @Test
    public void addPathParamWithEncoding() throws Exception {
        String url = new UrlBuilder("http://foo.com/{query}/")
                .addPathParam("query", "Alamo Square")
                .build();

        assertEquals("http://foo.com/Alamo%20Square/", url);
    }

    @Test
    public void addPathParamWithoutEncoding() throws Exception {
        String url = new UrlBuilder("http://foo.com/{query}/")
                .addEncodedPathParam("query", "Alamo Square")
                .build();

        assertEquals("http://foo.com/Alamo Square/", url);
    }

    @Test
    public void addQueryParams() throws Exception {
        String url = new UrlBuilder("http://foo.com")
                .addQueryParam("first_name", "John")
                .addQueryParam("last_name", "Doe")
                .build();

        assertEquals("http://foo.com?first_name=John&last_name=Doe", url);
    }

    @Test
    public void addQueryParamWithEncoding() throws Exception {
        String url = new UrlBuilder("http://foo.com")
                .addQueryParam("search", "Alamo Square")
                .build();

        assertEquals("http://foo.com?search=Alamo+Square", url);
    }

    @Test
    public void addQueryParamWithoutEncoding() throws Exception {
        String url = new UrlBuilder("http://foo.com")
                .addEncodedQueryParam("search", "Alamo Square")
                .build();

        assertEquals("http://foo.com?search=Alamo Square", url);
    }

    @Test(expected=IllegalArgumentException.class)
    public void nullQueryParamName() throws Exception {
        new UrlBuilder("http://foo.com")
                .addQueryParam(null, "John")
                .build();
    }

    @Test(expected=IllegalArgumentException.class)
    public void nullQueryParamValue() throws Exception {
        new UrlBuilder("http://foo.com")
                .addQueryParam(null, "John")
                .build();
    }
}
