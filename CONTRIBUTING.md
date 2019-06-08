Contributing
============

Keeping the project small and stable limits our ability to accept new contributors. We are not
seeking new committers at this time, but some small contributions are welcome.

If you've found a security problem, please follow our [bug bounty](BUG-BOUNTY.md) program.

If you've found a bug, please contribute a failing test case so we can study and fix it.

If you have a new feature idea, please build it in an external library. There are
[many libraries](WORKS_WITH_OKHTTP.md) that sit on top or hook in via existing APIs. If you build
something that integrates with OkHttp, tell us so that we can link it!

Before code can be accepted all contributors must complete our
[Individual Contributor License Agreement (CLA)](https://spreadsheets.google.com/spreadsheet/viewform?formkey=dDViT2xzUHAwRkI3X3k5Z0lQM091OGc6MQ&ndplr=1).


Code Contributions
------------------

Get working code on a personal branch with tests passing before you submit a PR:

```
./gradlew clean check
```

Please make every effort to follow existing conventions and style in order to keep the code as
readable as possible.

Contribute code changes through GitHub by forking the repository and sending a pull request. We
squash all pull requests on merge.


Committer's Guides
------------------

 * [Concurrency](CONCURRENCY.md)
 * [Releasing](RELEASING.md)
