package okhttp3

enum class BinaryMode {
  HEX, // hex encode
  OMIT, // "[binary body omitted]"
  FILE, // --data-binary @filename
  STDIN, // --data-binary @-
}
