Security Policy
===============

## Supported Versions

| Version | Supported        | Notes          |
| ------- | ---------------- | -------------- |
| 4.x     | ✅               |  Android 5.0+ (API level 21+) and on Java 8+. |
| 3.14.x  | Until 2020-06-30 |                |
| 3.12.x  | Until 2021-12-31 | Android 2.3+ (API level 9+) and Java 7+. Platforms may not support TLSv1.2. |

## Securing Older Android Clients

Applications expected to be installed on older Android devices should consider adopting the Google Play 
Services Provider. This will increase security for users and reduce frequent issues with incompatiblities 
with modern webservers that assume a secure set of cipher suites and TLS version.

https://developer.android.com/training/articles/security-gms-provider

The handshake result is generally an intersection of the Device, Android Version, OkHttp version and WebServer.
If you get handshake failures on older devices, start by looking at whether adopting the GMS Provider will help.
Conversely look at whether the server you are connecting to is secure.

OkHttp follows security best practices and will fail requests rather than make insecure connections.
See our policies here.

https://github.com/square/okhttp/blob/master/docs/tls_configuration_history.md

This type of problem will typical surface as an error like

```
Caused by: javax.net.ssl.SSLProtocolException: SSL handshake aborted: ssl=0x7f2719a89e80: Failure in SSL library, usually a protocol error
error:14077410:SSL routines:SSL23_GET_SERVER_HELLO:sslv3 alert handshake failure (external/openssl/ssl/s23_clnt.c:770 0x7f2728a53ea0:0x00000000)
at com.android.org.conscrypt.NativeCrypto.SSL_do_handshake(Native Method)
```

## Reporting a Vulnerability

Square recognizes the important contributions the security research community
can make. We therefore encourage reporting security issues with the code
contained in this repository.

If you believe you have discovered a security vulnerability, please follow the
guidelines at https://bugcrowd.com/squareopensource
