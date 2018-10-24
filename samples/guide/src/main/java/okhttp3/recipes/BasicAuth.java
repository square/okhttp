package okhttp3.recipes;

import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class BasicAuth {
    private final OkHttpClient client;

    public BasicAuth() {
        client = new OkHttpClient.Builder()
                .addInterceptor(new BasicAuthInterceptor("jesse", "password1"))
                .build();
    }

    public void run() throws Exception {
        Request request = new Request.Builder()
                .url("https://publicobject.com/secrets/hellosecret.txt")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            System.out.println(response.body().string());
        }
    }

    public static void main(String... args) throws Exception {
        new BasicAuth().run();
    }
}

class BasicAuthInterceptor implements Interceptor {

    private final String credentials;

    public BasicAuthInterceptor(String username, String password) {
        this.credentials = Credentials.basic(username, password);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Request newRequest = request.newBuilder()
                .header("Authorization", credentials)
                .build();
        return chain.proceed(newRequest);
    }
}
