IDN Data
========

In order to implement Nameprep (RFC 3491), OkHttp uses Unicode tables specified in Stringprep
(RFC 3454). Fragments of this RFC are dumped into the files in this directory and parsed by
`StringprepTablesReader` into a model that can be used at runtime.

This format is chosen to make it easy to validate that these tables are consistent with the RFC.

```
cd okhttp/src/jvmTest/resources/okhttp3/internal/idn/
ls rfc3454.*.txt | xargs -n 1 -I {} bash -c "echo {} ; cat {}" > okhttp_tables.txt
curl https://www.rfc-editor.org/rfc/rfc3454.txt > rfc3454.txt
diff rfc3454.txt okhttp_tables.txt | less
```

