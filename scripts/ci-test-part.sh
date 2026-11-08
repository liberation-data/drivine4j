#!/usr/bin/env bash
# Runs one part of CI's tests: what one of ci.yml's test jobs runs, and nightly.yml's and release.yml's through it.
#
#   scripts/ci-test-part.sh <variant> <part>
#
# <variant> is the store, or the sort strategy on Neo4j, the job is of: APOC_SORT_MAPS, CALL_SUBQUERY, FALKORDB,
# MEMGRAPH, ORACLE or POSTGRES. <part> is
#
# - i/n, the i-th of n shards of the pull request set: the variant's tests without the classes marked @Nightly
#   (org.drivine.test.Nightly), dealt out over n jobs by gradle/ci-test-sets.gradle;
# - nightly, the classes marked @Nightly alone, which a full run adds to the shards: each night (nightly.yml), on a push
#   to main, in a release's gate, and on a pull request labelled full-ci;
# - spring, on ORACLE alone: the sample app's, the coroutines module's and the root module's Spring tests on an Oracle
#   datasource, which hold no nightly class;
# - all, on FALKORDB, MEMGRAPH and POSTGRES: their jobs, which hold no nightly class either.
#
# Each variant runs what its job ran before the shards, in the same steps, and leaves its results where that job did:
# before a step runs the root module again, the results of the one before move to build/test-results-<name>, and so
# do the root module's results of an Oracle shard, as there they were moved for the step after. FalkorDB's and
# Memgraph's jobs no longer run the whole suite again: the jobs on Neo4j run it, its cross-engine tests on every engine.
set -euo pipefail

variant=${1:?Usage: scripts/ci-test-part.sh <variant> <part>}
part=${2:?Usage: scripts/ci-test-part.sh <variant> <part>}

gradle=(./gradlew --no-daemon --init-script gradle/ci-test-sets.gradle)

# The selection of a part of the pull request set, or of the nightly set, and the kind of part it is.
case "$part" in
  nightly) kind=nightly select=(-Pdrivine.test.set=nightly) ;;
  [1-9]*/[1-9]*) kind=shard select=(-Pdrivine.test.set=pr "-Pdrivine.test.shard=$part" "-Pdrivine.test.weights=$variant") ;;
  spring | all) kind=$part select=() ;;
  *) echo "The part is i/n, nightly, spring or all, not '$part'." >&2; exit 2 ;;
esac
# Stops unless the part is of a kind the variant has.
kinds() {
  if [[ " $* " != *" $kind "* ]]; then echo "$variant has no part '$part': its parts are $*." >&2; exit 2; fi
}

# The root module's results so far, moved for the report before a step that runs it again replaces them.
keep() {
  rm -rf "build/test-results-$1"
  if [[ -d build/test-results/test ]]; then mv build/test-results/test "build/test-results-$1"; fi
}

# The stateless manager's Spring tests in the root module, which read the datasource 'neo': run on each store's
# datasource, as the environment points it at the store.
stateless=(
  --tests 'org.drivine.annotation.StatelessSortedByTests'
  --tests 'org.drivine.manager.StatelessCountTests'
  --tests 'org.drivine.manager.StatelessDefaultAnnotationE2ETest'
  --tests 'org.drivine.manager.StatelessDeleteTests'
  --tests 'org.drivine.manager.StatelessFragmentLoadingTests'
  --tests 'org.drivine.manager.StatelessFragmentSaveTests'
  --tests 'org.drivine.manager.StatelessGraphObjectManagerSubtypeTests'
  --tests 'org.drivine.manager.StatelessGraphViewSaveTests'
  --tests 'org.drivine.manager.StatelessTemporalE2ETest'
  --tests 'org.drivine.manager.StatelessTransactionTests'
  --tests 'org.drivine.manager.UndeclaredReadOnlyFieldSaveTest'
  --tests 'org.drivine.model.StatelessGraphRelationshipFragmentEndToEndTests'
  --tests 'org.drivine.model.StatelessInterfaceNodeFragmentTests'
  --tests 'org.drivine.query.StatelessGraphPathE2ETest'
  --tests 'org.drivine.query.StatelessRecursiveGraphViewEndToEndTests'
  --tests 'sample.JavaStatelessSaveTests'
  --tests 'sample.StatelessJavaGraphObjectManagerSubtypeTests'
)
# The tests that seed, write and read back in Cypher through a PersistenceManager: on a SQL store, the Cypher compiled.
persistence=(
  --tests 'org.drivine.manager.PersistenceManagerTests'
  --tests 'org.drivine.manager.QueryBatchInTransactionTests'
  --tests 'org.drivine.manager.RowMapperTests'
  --tests 'org.drivine.manager.ScalarResultTests'
  --tests 'org.drivine.manager.FilterIsInstanceTests'
  --tests 'org.drivine.mapper.EnumTransformTests'
  --tests 'org.drivine.query.BindConversionTests'
  --tests 'org.drivine.transaction.TransactionRollbackTests'
  --tests 'sample.simple.EventRepositoryTests'
  --tests 'sample.simple.HolidayRepositoryTests'
  --tests 'sample.simple.HolidayingPersonTests'
  --tests 'sample.simple.PersonRepositoryTests'
  --tests 'sample.simple.PolymorphicTests'
  --tests 'org.drivine.manager.NonTransactionalBatchTest'
  --tests 'org.drivine.manager.BatchCommitFailureTest'
  --tests 'org.drivine.manager.NonAtomicBatchesTest'
)
# The sample app's stateless tests, on a SQL store's datasource. Its tests seed in Cypher, which the compiler makes SQL
# of over the graph's tables (SqlCypherFixtures.forStore). The deprecated GraphObjectManager's are left out: its save is
# Cypher alone.
sample=(
  --tests '*Stateless*'
  --tests 'org.drivine.sample.StampFilterDslTest'
  --tests 'org.drivine.sample.OddStoredNameDslTest'
)

# The sample module's datasource, on a graph engine other than Neo4j.
sample_on() {
  cat > drivine4j-sample/src/test/resources/application.yaml
}

case "$variant" in
  # The whole suite on Neo4j, with each collection sort strategy. standaloneTest runs the core on its own runtime
  # classpath, as a plain main or a Ktor server has it. The Postgres tests don't read the sort strategy, so
  # APOC_SORT_MAPS runs them and CALL_SUBQUERY turns them off, and they run once.
  APOC_SORT_MAPS | CALL_SUBQUERY)
    kinds shard nightly
    if [[ "$variant" == APOC_SORT_MAPS ]]; then export POSTGRES_TESTS=true; else export POSTGRES_TESTS=false; fi
    DATABASE_DATASOURCES_NEO_COLLECTION_SORT_STRATEGY=$variant "${gradle[@]}" test standaloneTest "${select[@]}"
    ;;

  # What only the engine's job runs: the sample module on a datasource of the engine, and the root module's Spring
  # tests the engine can run, on a datasource 'neo' the environment points at it. FalkorDB's are those that don't rely
  # on a rolled-back transaction to clean up after themselves, which it doesn't have; Memgraph's leave out the tests
  # that use DELETE_ORPHAN or sort a collection in a way it doesn't support. The rest of the suite, the cross-engine
  # tests on FalkorDB and Memgraph among it, runs in APOC_SORT_MAPS's and CALL_SUBQUERY's jobs, as it did here too.
  FALKORDB | MEMGRAPH)
    kinds all
    export POSTGRES_TESTS=false
    if [[ "$variant" == FALKORDB ]]; then
      sample_on << 'EOF'
spring:
  profiles:
    active: test
  main:
    banner-mode: off
logging:
  level:
    org.drivine: DEBUG
database:
  datasources:
    graph:
      type: FALKORDB
      host: localhost
      port: 6379
      database-name: testgraph
EOF
    else
      sample_on << 'EOF'
spring:
  profiles:
    active: test
  main:
    banner-mode: off
logging:
  level:
    org.drivine: DEBUG
database:
  datasources:
    graph:
      type: MEMGRAPH
      host: localhost
      port: 7687
      user-name: ""
      password: ""
EOF
    fi
    "${gradle[@]}" :drivine4j-sample:test
    if [[ "$variant" == FALKORDB ]]; then
      DATABASE_DATASOURCES_NEO_TYPE=FALKORDB DATABASE_DATASOURCES_NEO_PORT=6379 DATABASE_DATASOURCES_NEO_DATABASE_NAME=testgraph \
        "${gradle[@]}" :test \
        --tests 'org.drivine.manager.StatelessTransactionTests' \
        --tests 'org.drivine.manager.UndeclaredReadOnlyFieldSaveTest' \
        --tests 'sample.JavaStatelessSaveTests'
    else
      DATABASE_DATASOURCES_NEO_TYPE=MEMGRAPH DATABASE_DATASOURCES_NEO_USER_NAME="" DATABASE_DATASOURCES_NEO_PASSWORD="" \
        DATABASE_DATASOURCES_NEO_DATABASE_NAME=memgraph "${gradle[@]}" :test "${stateless[@]}"
    fi
    ;;

  # The Oracle tests are left out of a plain `./gradlew test`, which the jobs above run: ORACLE_TESTS enables them.
  # The shards run the root module's, and spring the rest on an Oracle datasource, as the Postgres job does: a graph
  # engine's job proves nothing of the SQL Drivine writes for Oracle.
  ORACLE)
    kinds shard nightly spring
    export POSTGRES_TESTS=false
    if [[ "$kind" != spring ]]; then
      ORACLE_TESTS=true "${gradle[@]}" :test --tests '*Oracle*' "${select[@]}"
      keep default
    else
      ORACLE_TESTS=true "${gradle[@]}" :drivine4j-coroutines:test --tests '*Oracle*'
      DATABASE_DATASOURCES_GRAPH_TYPE=ORACLE "${gradle[@]}" :drivine4j-sample:test "${sample[@]}"
      DATABASE_DATASOURCES_NEO_TYPE=ORACLE "${gradle[@]}" :test "${stateless[@]}"
      keep spring
      DATABASE_DATASOURCES_NEO_TYPE=ORACLE "${gradle[@]}" :test "${persistence[@]}"
    fi
    ;;

  # The Postgres tests run in a plain `./gradlew test`, so APOC_SORT_MAPS runs them. This runs the sample app's and the
  # root module's Spring tests on a Postgres datasource, which no other job does: neither a graph engine's job nor
  # Oracle's proves anything of the SQL Drivine writes for Postgres. Each run names its classes, and POSTGRES_TESTS=false
  # keeps the Postgres tests out should one name none.
  POSTGRES)
    kinds all
    export POSTGRES_TESTS=false
    DATABASE_DATASOURCES_GRAPH_TYPE=POSTGRES "${gradle[@]}" :drivine4j-sample:test "${sample[@]}"
    DATABASE_DATASOURCES_NEO_TYPE=POSTGRES "${gradle[@]}" :test "${stateless[@]}"
    keep spring
    DATABASE_DATASOURCES_NEO_TYPE=POSTGRES "${gradle[@]}" :test "${persistence[@]}"
    ;;

  *)
    echo "The variant is APOC_SORT_MAPS, CALL_SUBQUERY, FALKORDB, MEMGRAPH, ORACLE or POSTGRES, not '$variant'." >&2
    exit 2
    ;;
esac
