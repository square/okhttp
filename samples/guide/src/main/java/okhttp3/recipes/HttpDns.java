package okhttp3.recipes;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * using httpdns ,not using localdns sample
 * Created by lizhangqu on 16/4/12.
 */
public final class HttpDns {
    public void run() throws Exception {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(new HttpDnsInterceptor()).build();

        Request request = new Request.Builder()
                .url(HttpDnsInterceptor.BAIDU_URL)
                .get()
                .build();

        Response execute = okHttpClient.newCall(request).execute();

        System.out.println(execute.body().string());
    }

    public static void main(String... args) throws Exception {
        new HttpDns().run();
    }

    static class HttpDnsInterceptor implements Interceptor {
        public static final String BAIDU_URL = "https://www.baidu.com";
        public static final String BAIDU_DOMAIN = "www.baidu.com";
        public static final String BAIDU_IP = "220.181.112.244";

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request orignalRequest = chain.request();
            Request newRequest = orignalRequest.newBuilder()
                    //set Host
                    .header("Host", BAIDU_DOMAIN)
                    //replace domain in url with ip,the ip get from httpdns,this is a mock ip
                    .url(orignalRequest.url().toString().replace(BAIDU_DOMAIN, BAIDU_IP))
                    .build();
            Response response = chain.proceed(newRequest);
            return response;
        }
    }
}
