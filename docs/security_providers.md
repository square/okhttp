
Security Providers
==================

## Provider Status

| Provider            | Issues                                                | Link                                                        | Usage Guidance |
| ------------------- | ----------------------------------------------------- | ----------------------------------------------------------- | -------------- |
| Bouncy Castle       | [#5698](https://github.com/square/okhttp/issues/5698) | https://www.bouncycastle.org/java.html                      | Clean-Room JSSE implementation |
| Conscrypt - Android | ✅                                                    |  https://www.conscrypt.org/                                 | TLS 1.3 via BoringSSL. Activated if Conscrypt is first registered provider. |
| Conscrypt - OpenJDK | ✅                                                    | https://www.conscrypt.org/                                  | TLS 1.3 via BoringSSL. Activated if Conscrypt is first registered provider. |
| OpenJSSE            | ✅ Missing HTTP/2                                     | https://github.com/openjsse/openjsse                        | Supports TLS 1.3 on Java SE 8 |
| Corretto            | [#5592](https://github.com/square/okhttp/issues/5592) | https://github.com/corretto/amazon-corretto-crypto-provider | Amazon high-performance cryptographic implementations backed by OpenSSL |


