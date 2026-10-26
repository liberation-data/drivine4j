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
