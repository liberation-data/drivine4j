# Oracle: a join `ON TRUE` in a scalar subquery is refused, or crashes the session

A standalone repro of an Oracle server bug, in plain SQL, for an Oracle service request or a forum post. Drivine works
around it (liberation-data/drivine4j#338; see `OracleCypherCompiler.lateral`), and the workaround can go once a release
of Oracle no longer fails this repro.

## Summary

A join whose condition is the literal `TRUE` (`LEFT JOIN … ON TRUE`, `JOIN … ON TRUE`), inside a scalar subquery, is
mis-parsed. Followed by a `WHERE` with an `AND`, the statement is refused with ORA-00907 (missing right parenthesis).
Without one, the server process dies while compiling the statement (ORA-07445 in `qcsjccqb`), and the client gets
ORA-03113.

- **Seen on:** Oracle AI Database 26ai Free, Release 23.26.3.0.0 (container image `gvenzl/oracle-free:23-faststart`, on
  an arm64 host under Docker). Not tried on other versions or platforms.
- **Needs:** the join on `TRUE` (also `ON (TRUE)`) in a scalar subquery: a subquery in the select list, as
  `SELECT (SELECT count(*) FROM …) FROM dual`.
- **Doesn't fail:** the same query at the top of a statement or as an inline view; the same scalar subquery joined
  `ON 1 = 1`, or by `OUTER APPLY`; a `CROSS JOIN … WHERE TRUE`; and the join on `TRUE` in an `EXISTS (…)`.

## Repro

Run in one SQL*Plus session as any user (`sqlplus user/password@FREEPDB1 @repro.sql`). No table is needed:

```sql
SET FEEDBACK ON
-- Refused: ORA-00907 at the AND.
SELECT (SELECT count(*) FROM dual d LEFT JOIN dual e ON TRUE WHERE 1 = 1 AND 2 = 2) AS c FROM dual;
-- Runs: 1.
SELECT (SELECT count(*) FROM dual d LEFT JOIN dual e ON 1 = 1 WHERE 1 = 1 AND 2 = 2) AS c FROM dual;
SELECT 'session alive' AS status FROM dual;
-- Crashes the server process: ORA-03113.
SELECT (SELECT count(*) FROM dual d LEFT JOIN dual e ON TRUE) AS c FROM dual;
SELECT 'session alive' AS status FROM dual;
EXIT
```

Then look at the alert log (on the image above, `/opt/oracle/diag/rdbms/free/FREE/trace/alert_FREE.log`):

```sh
grep -E 'ORA-00600|ORA-07445' /opt/oracle/diag/rdbms/free/FREE/trace/alert_FREE.log
```

## Expected

Each `SELECT` returns 1, the session stays open, and the alert log has no internal error.

## Actual

```
SELECT (SELECT count(*) FROM dual d LEFT JOIN dual e ON TRUE WHERE 1 = 1 AND 2 = 2) AS c FROM dual
                                                                         *
ERROR at line 1:
ORA-00907: missing right parenthesis
```

The query joined `ON 1 = 1` returns 1. The last query loses the session:

```
ERROR at line 1:
ORA-03113: end-of-file on communication channel
```

and the alert log has:

```
ORA-07445: exception encountered: core dump [qcsjccqb()+160] [SIGSEGV] [ADDR:0x20000] [PC:0x13534640] [Address not mapped to object] []
```

## Workaround

Join on a condition other than the literal: Drivine's Oracle compiler writes an optional lateral join (an `OPTIONAL CALL`
or an `OPTIONAL MATCH`) as `LEFT JOIN LATERAL (…) cN ON 1 = 1`, where the other stores write `ON TRUE`. A Cypher
`COUNT { … }` is compiled to a scalar subquery, so such a join in one met this bug, in a read and in a write alike.
