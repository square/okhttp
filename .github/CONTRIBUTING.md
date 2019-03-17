Contributing
============

If you would like to contribute code to OkHttp you can do so through GitHub by
forking the repository and sending a pull request.

When submitting code, please make every effort to follow existing conventions
and style in order to keep the code as readable as possible. Please also make
sure your code compiles by running `./gradlew check`. Checkstyle failures
during compilation indicate errors in your style and can be viewed in the
`checkstyle-result.xml` file.

Some general advice

- Don’t change public API lightly, avoid if possible, and include your reasoning in the PR if essential.  It causes pain for developers who use OkHttp and sometimes runtime errors.
- Favour a working external library if appropriate.  There are many examples of OkHttp libraries that can sit on top or hook in via existing APIs.
- Get working code on a personal branch with tests before you submit a PR.
- OkHttp is a small and light dependency.  Don't introduce new dependencies or major new functionality.
- OkHttp targets the intersection of RFC correct *and* widely implemented.  Incorrect implementations that are very widely implemented e.g. a bug in Apache, Nginx, Google, Firefox should also be handled.

Before your code can be accepted into the project you must also sign the
[Individual Contributor License Agreement (CLA)][1].


 [1]: https://spreadsheets.google.com/spreadsheet/viewform?formkey=dDViT2xzUHAwRkI3X3k5Z0lQM091OGc6MQ&ndplr=1
