OkHttp4 and Latest
==================

This project validates that a project that has transitive dependencies on OkHttp 4.x can upgrade
to OkHttp 5.x without problems.

This is non-trivial because OkHttp 5.x changed the project's binary artifact name from 'okhttp' to
'okhttp-jvm' or 'okhttp-android' a side-effect of becoming a multiplatform project.
