
Three Drivine-relevant findings from RAG benchmarking this week

1. Vector index DDL inherits server defaults that changed under us. Neo4jSchemaGrammar's CREATE VECTOR INDEX sets only vector.dimensions and vector.similarity_function. Neo4j fills in the rest at creation time — on 2026.04 that means vector.quantization.enabled: true, hnsw.m: 16, ef_construction: 100. So the same schema declaration produces different physical indexes across Neo4j versions, silently. Suggestion: let VectorIndexSpec optionally pin quantization and HNSW parameters (and perhaps log the effective config after creation), so a declared schema is reproducible. We chased quantization as a suspect during an incident precisely because nobody had chosen it.

2. Filtered ANN reads have a sharp small-k recall failure mode worth documenting or defending against. On a ~9K-vector index (1536-dim cosine), after a bulk insert of ~2K tightly-clustered vectors, db.index.vector.queryNodes failed to return a vector we verified as true global rank 3 (by exhaustive vector.similarity.cosine) at every k ≤ 100 — while k=200 returned it at rank 3. This reproduced across two full index rebuilds, with quantization on and off, and across a database restart, so it looks like genuine HNSW navigation failure on clustered data rather than transient state. Two compounding effects hit Drivine-style filtered queries (loadNearest + where{}): the ANN read itself under-recalls at practical k, and the filter applies after the index yield, so a tenant-scoped caller can receive far fewer than k rows, all from the wrong region. Our workaround is caller-side over-fetch (fetch 200, trim to 40). If Drivine wants to own this, options are an over-fetch multiplier on filtered loadNearest, or exposing the effective-k knob so callers can reason about it.

3. Thanks for the fast turnarounds — the HAS_ELEMENT/predicateOn support in 0.0.73 fixed a class of UnsupportedOperationExceptions across our corpus suites (everything green after), and the blank-index-name fix (PR #9 + your whitespace follow-up in 0.0.78) closes a real boot failure on fresh databases.

Repro details for #2 available on request: exact queries, index configs, and the exhaustive-vs-ANN comparison script.


