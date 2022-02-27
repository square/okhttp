Security
========

## Supported Versions

| Version | Supported           | Notes                                        |
| ------- | ------------------- | -------------------------------------------- |
| 5.x     | ✅                  | APIs subject to change in alpha releases.    |
| 4.x     | ✅                  | Android 5.0+ (API level 21+) and on Java 8+. |
| 3.x     | ❌ Ended 2021-12-31 | Android 2.3+ (API level 9+) and Java 7+.     |


## Reporting a Vulnerability

Square recognizes the important contributions the security research community
can make. We therefore encourage reporting security issues with the code
contained in this repository.

If you believe you have discovered a security vulnerability, please follow the
guidelines at https://bugcrowd.com/squareopensource


## Verifying Artifacts

We sign our artifacts using this [key][signing_key]:

```
pub rsa4096/dbd744ace7ade6aa50dd591f66b50994442d2d40 2021-07-09T14:50:19Z
	 Hash=a79b48fd6a1f31699c788b50c97d0b98

uid Square Clippy <opensource@squareup.com>
sig  sig  66b50994442d2d40 2021-07-09T14:50:19Z 2041-07-04T14:50:19Z ____________________ [selfsig]
```

The best way to verify artifacts is [automatically with Gradle][gradle_verification].


[gradle_verification]: https://docs.gradle.org/current/userguide/dependency_verification.html#sec:signature-verification
[signing_key]: https://keyserver.ubuntu.com/pks/lookup?op=hget&search=a79b48fd6a1f31699c788b50c97d0b98
