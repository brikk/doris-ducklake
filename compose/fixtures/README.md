# BOOLEAN dictionary interoperability fixture

`boolean_rle_dictionary.parquet.b64` is the unmodified 257-byte Apache Doris
fixture from commit `9fb3e3a2a13f972a62cb999d5599fc8e8f17f306`, path
`regression-test/data/external_table_p0/tvf/test_data/boolean_rle_dictionary.parquet`.
It was added with [#69967](https://github.com/apache/doris/pull/69967) and is covered
by the upstream Apache License 2.0. Base64 keeps the exact binary bytes in a text
patch; decode before uploading, never rewrite it with a Parquet writer.

Decoded SHA-256:
`ba2016dcf7ba8af572371491f0b6701afb0b79c06a85930a80598628a020e5b2`.

Columns are `id` (PLAIN INT32) and nullable `b` (BOOLEAN,
`PLAIN, RLE, RLE_DICTIONARY`). Ordered rows have ids 0..9 and boolean values
`true, false, NULL, true, true, false, NULL, false, true, true`.

The owned read-correctness controller uploads these exact bytes and registers
them through DuckLake `add_data_files`; live tests check both the DuckLake
connector and native S3 TVF, with lazy materialization off/on. Aggregate counts
alone are not sufficient: ordered values, NULL alignment and predicates must agree.
