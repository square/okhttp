/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.recipes;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.duplex.MwsDuplexAccess;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.internal.duplex.MockDuplexResponseBody;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Pipe;

public final class DuplexCall {
  private OkHttpClient client;
  private final MockWebServer server;
  private final HandshakeCertificates handshakeCertificates = generateHandshakeCertificates();

  public DuplexCall() {
    this.client = new OkHttpClient.Builder()
        // Enable Tls
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        // Enable Http2
        .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build();

    this.server = new MockWebServer();
    setupServer();
  }

  public void run() throws IOException {
    RequestBody duplexRequestBody = new DuplexRequestBody(1024 * 1024);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(duplexRequestBody)
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((DuplexRequestBody) call.request().body()).createSink();
      requestBody.writeUtf8("request A");
      requestBody.flush();

      BufferedSource responseBody = response.body().source();
      System.out.println(responseBody.readUtf8Line());

      requestBody.writeUtf8("request B");
      requestBody.flush();

      System.out.println(responseBody.readUtf8Line());

      requestBody.close();
    }
  }

  private final class DuplexRequestBody extends RequestBody {
    private final Pipe pipe;

    private DuplexRequestBody(long pipeMaxBufferSize) {
      this.pipe = new Pipe(pipeMaxBufferSize);
    }

    public BufferedSink createSink() {
      return Okio.buffer(pipe.sink());
    }

    @Override public MediaType contentType() {
      return null;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      pipe.fold(sink);
    }

    @Override public boolean isDuplex() {
      return true;
    }
  }

  private HandshakeCertificates generateHandshakeCertificates() {
    try {
      // Generate a self-signed cert for the server to serve and the client to trust.
      HeldCertificate heldCertificate = new HeldCertificate.Builder()
          .commonName("localhost")
          .addSubjectAlternativeName(InetAddress.getByName("localhost").getCanonicalHostName())
          .build();

      return new HandshakeCertificates.Builder()
          .heldCertificate(heldCertificate)
          .addTrustedCertificate(heldCertificate.certificate())
          .build();
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  private void setupServer() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocols(client.protocols());

    MockResponse response = new MockResponse()
        .clearHeaders();
    MwsDuplexAccess.instance.setBody(response, new MockDuplexResponseBody()
        .receiveRequest("request A\n")
        .sendResponse("response B\n")
        .receiveRequest("request C\n")
        .sendResponse("response D\n")
        .exhaustRequest()
        .exhaustResponse());
    server.enqueue(response);
  }

  private final class RecordingHostnameVerifier implements HostnameVerifier {
    public final List<String> calls = new ArrayList<>();

    public boolean verify(String hostname, SSLSession session) {
      calls.add("verify " + hostname);
      return true;
    }
  }

  public static void main(String[] args) throws IOException {
    new DuplexCall().run();
  }
}
