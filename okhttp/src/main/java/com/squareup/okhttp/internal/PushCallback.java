package com.squareup.okhttp.internal;

import com.squareup.okhttp.Response;
import okio.BufferedSource;

/**
 * Created by drapp on 5/22/14.
 */
public interface PushCallback {

    boolean onPush(Response partialResponse, BufferedSource buffer);
}
