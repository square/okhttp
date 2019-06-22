package okhttp3

import java.io.File

val prefix = if (File("README.md").exists()) "" else "../../"