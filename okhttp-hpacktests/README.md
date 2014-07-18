OkHttp HPACK tests
==================

These tests use the [hpack-test-case][1] project to validate OkHttp's HPACK
implementation.  The HPACK test cases are in a separate git submodule, so to
initialize them, you must run:

    git submodule init
    git submodule update

When new interop tests are available, you should update
HpackDecodeInteropGoodTest#GOOD_INTEROP_TESTS with the directory name.

TODO
----

 * Add maven goal to avoid manual call to git submodule init.
 * Make hpack-test-case update itself from git, and run new tests.
 * Add maven goal to generate stories and a pull request to hpack-test-case
   to have others validate our output.

[1]: https://github.com/http2jp/hpack-test-case 
