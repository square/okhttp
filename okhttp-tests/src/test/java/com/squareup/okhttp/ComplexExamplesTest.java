// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import org.junit.Test;

import static com.squareup.okhttp.TestUtils.UTF_8;
import static org.junit.Assert.assertEquals;

public class ComplexExamplesTest {
  @Test public void fieldAndTwoFiles() throws Exception {
    String expected = ""
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"submit-name\"\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n"
        + "Larry\r\n"
        + "--AaB03x\r\n"
        + "Content-Disposition: form-data; name=\"files\"\r\n"
        + "Content-Type: multipart/mixed; boundary=BbC04y\r\n"
        + "\r\n"
        + "--BbC04y\r\n"
        + "Content-Disposition: file; filename=\"file1.txt\"\r\n"
        + "Content-Type: text/plain\r\n"
        + "Content-Length: 29\r\n"
        + "\r\n"
        + "... contents of file1.txt ...\r\n"
        + "--BbC04y\r\n"
        + "Content-Disposition: file; filename=\"file2.gif\"\r\n"
        + "Content-Type: image/gif\r\n"
        + "Content-Length: 29\r\n"
        + "Content-Transfer-Encoding: binary\r\n"
        + "\r\n"
        + "... contents of file2.gif ...\r\n"
        + "--BbC04y--\r\n"
        + "--AaB03x--";

    Multipart m = new Multipart.Builder("AaB03x") //
        .type(Multipart.Type.FORM) //
        .addPart(new Part.Builder() //
            .contentDisposition("form-data; name=\"submit-name\"") //
            .body("Larry") //
            .build()) //
        .addPart(new Part.Builder() //
            .contentDisposition("form-data; name=\"files\"") //
            .body(new Multipart.Builder("BbC04y") //
                .addPart(new Part.Builder() //
                    .contentDisposition("file; filename=\"file1.txt\"") //
                    .contentType("text/plain") //
                    .body("... contents of file1.txt ...") //
                    .build()) //
                .addPart(new Part.Builder() //
                    .contentDisposition("file; filename=\"file2.gif\"") //
                    .contentType("image/gif") //
                    .contentEncoding("binary") //
                    .body("... contents of file2.gif ...") //
                    .build()) //
                .build()) //
            .build()) //
        .build();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    m.writeBodyTo(out);
    String actual = new String(out.toByteArray(), UTF_8);
    assertEquals(expected, actual);
    assertEquals(Collections.singletonMap("Content-Type", "multipart/form-data; boundary=AaB03x"),
        m.getHeaders());
  }
}
