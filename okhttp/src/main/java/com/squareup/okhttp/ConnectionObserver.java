package com.squareup.okhttp;

import java.io.IOException;

public interface ConnectionObserver {
    /**
     * Invoked when an exception occurs on the Connection's socket
     */
    void onIOException(Connection connection, IOException e);
}
