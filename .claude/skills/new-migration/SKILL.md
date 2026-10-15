---
name: new-migration
description: Add a Flyway migration with the next free version number, in the right folder, and check it on H2 and PostgreSQL.
disable-model-invocation: true
argument-hint: <what the migration does, e.g. "add memo column to saved_result">
---

Add a Flyway migration for: $ARGUMENTS

1. Find the next version: the highest `V<n>__` across `src/main/resources/db/migration/` **and** `src/main/resources/db/vendor/*/` (Flyway reads them together via `spring.flyway.locations`), plus one.
2. Pick the folder:
   - SQL that is the same on H2 and PostgreSQL → `db/migration/V<n>__<snake_case_name>.sql`
   - SQL that differs per database → the same file name in **both** `db/vendor/h2/` and `db/vendor/postgresql/`
3. Write SQL that runs on both H2 and PostgreSQL. Match the style of the existing migrations (lowercase keywords, tab indent).
4. Never edit an existing `V*.sql`; it is already applied in production and a hook blocks it.
5. Run `./gradlew test`. Mention whether Docker was running: without it `PostgresMigrationTest` is skipped and the migration was only checked on H2.
