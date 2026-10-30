# Oracle: JSON_QUERY of a string past 32 KB, over two rows, corrupts the session's memory

A standalone repro of an Oracle server bug, in plain SQL, for an Oracle service request or a forum post. Drivine works
around it (liberation-data/drivine4j#255; see `OracleCypherCompiler.itemProperty`), and the workaround can go once a
release of Oracle no longer fails this repro.

## Summary

Selecting a string value longer than about 32 KB out of a `JSON` column with `JSON_QUERY(… RETURNING JSON)`, over two
or more rows, corrupts the server process's memory. The server process dies, during that statement or a few statements
later, and the client gets ORA-03113; where it survives the statement, the alert log already has ORA-00600 `[17114]`.
The ORA-07445 that kills it faults at an address such as `0x78787878787878`: the bytes of the string, `xxxxxxx`, read as a
pointer, so the long value has been written over the heap.

- **Seen on:** Oracle AI Database 26ai Free, Release 23.26.3.0.0 (container image `gvenzl/oracle-free:23-faststart`,
  Oracle Linux 8.10, on an aarch64 host under Docker). Not tried on other versions or platforms.
- **Needs:** a string value past 32 KB (33,000 characters fail; 30,000 don't), read from two or more rows in one
  statement. It fails returned straight to the client and fed on through a subquery, an `ORDER BY` or a `JSON_ARRAY`.
- **Doesn't fail:** the same statement over one row; a list of 60 KB, or a map holding a 40 KB string, read the same
  way; and the same value read through its text instead (see [Workaround](#workaround)).

## Repro

Run as any user with `CREATE TABLE`, in one SQL*Plus session (`sqlplus user/password@FREEPDB1 @repro.sql`):

```sql
SET LONG 60 LONGCHUNKSIZE 60 LINESIZE 100 PAGESIZE 100 FEEDBACK ON
CREATE TABLE repro_node (
  nid   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  props JSON NOT NULL
);
-- Two rows, each holding a string of 40,000 characters under "a".
INSERT INTO repro_node (props)
SELECT JSON_TRANSFORM(JSON('{"id":' || level || '}'), SET '$.a' = RPAD(TO_CLOB('x'), 40000, 'x'))
FROM dual CONNECT BY level <= 2;
COMMIT;
-- The same read three times. The session is lost on one of them or at EXIT (see Actual).
SELECT JSON_QUERY(props, '$.a' RETURNING JSON) AS a FROM repro_node;
SELECT 'session alive 1' AS status FROM dual;
SELECT JSON_QUERY(props, '$.a' RETURNING JSON) AS a FROM repro_node WHERE nid > 0;
SELECT 'session alive 2' AS status FROM dual;
SELECT JSON_QUERY(props, '$.a' RETURNING JSON) AS a FROM repro_node WHERE nid > -1;
SELECT 'session alive 3' AS status FROM dual;
EXIT
```

Then look at the alert log (on the image above, `/opt/oracle/diag/rdbms/free/FREE/trace/alert_FREE.log`):

```sh
grep -E 'ORA-00600|ORA-07445' /opt/oracle/diag/rdbms/free/FREE/trace/alert_FREE.log
```

## Expected

Each `SELECT` returns the two strings, the session stays open, and the alert log has no internal error.

## Actual

The server process dies, and the alert log has internal errors. Where it dies varies from run to run. In a fresh
container, one run of this script lost the session on the first `SELECT`:

```
Table created.
2 rows created.
Commit complete.
ERROR:
ORA-03113: end-of-file on communication channel
Process ID: 265
Session ID: 57 Serial number: 38176
```

```
ORA-07445: exception encountered: core dump [kghsrch_new()+148] [SIGSEGV] [ADDR:0x78787878787868] [PC:0xFC738C4] [Address not mapped to object] []
ORA-07445: exception encountered: core dump [kghsrch_new()+148] [SIGSEGV] [ADDR:0x78787878787868] [PC:0xFC738C4] [Address not mapped to object] []
ORA-07445: exception encountered: core dump [kghxhal()+40] [SIGSEGV] [ADDR:0x78787878787878] [PC:0xFC94D78] [Address not mapped to object] []
```

Two other runs, each in a fresh container, returned the rows of all three selects, logged ORA-00600 `[17114]` twice,
and lost the session at `EXIT`, with ORA-07445 in `kxsFreePages` at `ADDR:0x78787878787878`. Other runs, through JDBC
and in statements that feed the value on through a subquery and an `ORDER BY`, logged ORA-00600 `[kghfrh1]`, `[17147]`
or `[17182]`, and ORA-07445 in `kghalo` or `kgscFreeCachedCursor`. Each ORA-07445 faults at an address made of the
string's bytes.

With 30,000 characters in place of 40,000, or `CONNECT BY level <= 1` in place of `2`, the same script runs clean.

## Workaround

Read the value through its text: keep only the key, serialize it to a CLOB, and read it back from that text. This runs
clean over any number of rows, and keeps the value's type, a float that is a whole number among them:

```sql
SELECT JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM(props, KEEP '$.a') RETURNING CLOB EXTENDED),
                       '$.a' RETURNING CLOB) EXTENDED) AS a
FROM repro_node;
```

It costs about 2.4 to 3.8 times the plain `JSON_QUERY` on short values (100,000 rows: 493 ms against 147 ms).

## In a PL/SQL block, from a `JSON` variable

The workaround above doesn't hold where the JSON is a PL/SQL `JSON` variable that a block sets once for each row it
loops over (liberation-data/drivine4j#309). Drivine's block for `UNWIND $rows AS r MATCH (n {id: r.id}) SET n.a = r.a`
loops over the rows, sets a `JSON` variable to each, and reads `r.a` from it in a `SELECT … INTO` of its own. Over three
rows of 40,000 characters, on the image above, each of these crashed the session (ORA-03113, with ORA-00600 `[17114]`
and ORA-07445 faulting at the string's bytes in the alert log):

```sql
SELECT JSON_QUERY(JSON_QUERY(row_, '$.v1' RETURNING JSON), '$.a' RETURNING JSON) INTO value_ FROM dual;
SELECT JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM(row_, KEEP '$.v1.a') RETURNING CLOB EXTENDED),
                       '$.v1.a' RETURNING CLOB) EXTENDED) INTO value_ FROM dual;
```

Serializing the whole variable, and reading the value from that text, ran clean in each of a few fresh containers:

```sql
SELECT JSON(JSON_QUERY(JSON_SERIALIZE(row_ RETURNING CLOB), '$.v1.a' RETURNING CLOB)) INTO value_ FROM dual;
```

So did serializing it with `EXTENDED` and reading it back as extended JSON. Drivine reads a text so, and a value of any
other type with the plain `JSON_QUERY`, which didn't crash for a list or a map that holds a long text (see
`OracleCypherCompiler.rowValue`). Unlike the repro above, these were seen only through Drivine's blocks, not in a
standalone script.

## An item of a list, read by its place

The same crash comes from a text past 32 KB taken out of a list as JSON (liberation-data/drivine4j#388): an item of a
list that `split` makes, of a stored list, or of one a `WITH` passes on, read with `JSON_QUERY(list, '$[0]' RETURNING
JSON)`, over two or more rows, closed the session from the second run of the statement on. Reading it from the list's
text ran clean in fresh containers, three runs each, and so did `JSON_VALUE(list, '$[0]' RETURNING CLOB)`:

```sql
JSON(JSON_QUERY(JSON_SERIALIZE(list RETURNING CLOB), '$[0]' RETURNING CLOB))
```

Drivine reads an item so where it is a text, and an item of any other type with the plain `JSON_QUERY` (see
`OracleCypherCompiler.listItem`). An unwound item, which `JSON_TABLE` takes out, ran clean as it was. A list made of a
long property, `JSON_ARRAY(JSON_QUERY(props, '$.a' RETURNING JSON), 'y')`, crashes in its making, and isn't worked around.

## Two texts, compared

The same crash comes from a text past 32 KB compared as JSON (liberation-data/drivine4j#389): `<`, `>`, `<=` or `>=` of
a property read with `JSON_QUERY(props, '$.a' RETURNING JSON)`, over two or more rows, closed the session from the first
run of the statement or a later one. `=` of such JSON is refused instead (ORA-40180), and the session stays open. Read
by the property's own path, `JSON_VALUE(props, '$.a' RETURNING CLOB)`, and compared with `DBMS_LOB.COMPARE`, it ran
clean in a fresh container, three runs:

```sql
DBMS_LOB.COMPARE(JSON_VALUE(n1.props, '$.a' RETURNING CLOB), JSON_VALUE(n2.props, '$.a' RETURNING CLOB))
```

Drivine compares two texts so where either is past 4,000 bytes, and as `VARCHAR2`s where neither is (see
`OracleCypherCompiler.jsonEquals` and `OracleCypherCompiler.ordered`). A list or a map that holds a long text is still
compared as JSON, and refused. So is a text in a statement that writes, or in the step of a `reduce()`, which run as
PL/SQL: there a CLOB read from an unwound row's JSON, `UNWIND $items AS t MERGE (n {a: t.a})`, closed the session,
where the JSON is refused (ORA-40180).
