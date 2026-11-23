# The three-way declarations run (liberation-data/drivine-cypher-spec#135)

This branch keeps the harness until it has a home; it isn't for merging. `interop.py` is the driver. drivine4j's half is `src/test/kotlin/org/drivine/schema/DeclarationsInteropTest.kt` with `DeclarationCases.kt`; it runs only when the driver sets `INTEROP_PHASE`.

The driver expects one directory laid out as:

    interop.py
    declarations.yaml   the spec's, at the tag run
    d4j/                drivine4j, with this branch's two test files
    ts/                 Drivine for TypeScript, with test/2.Integration/interop/Interop135.spec.ts and its node_modules
    py/                 drivine-python, with tests/interop and a .venv holding `pip install -e '.[compiler,sqlite,postgres,oracle]' --group dev`

Run it from that directory inside one docker-lock job. It starts and removes its own Postgres and Oracle containers:

    python3 interop.py --stores postgres,oracle,sqlite --libs d4j,ts,py

It writes each half's report and `out/results.json`. It fails on any change, failure or one-sided refusal outside a library's known gaps, and on any drift between a library's gap marks and the driver's `KNOWN_GAPS`.
