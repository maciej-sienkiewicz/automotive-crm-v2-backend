# Migration Instructions - Adding CONVERTED Status

## Problem
The application fails with constraint violation when converting appointments to visits because the `CONVERTED` status was added to the code but not to the database constraint.

## Solution
We've switched from Hibernate DDL auto-update to Flyway for proper schema management.

## Steps to Fix

### 1. Stop the Application
```bash
# If running via Gradle
pkill -f "detailing-crm"

# Or press Ctrl+C in the terminal where app is running
```

### 2. Rebuild the Application
```bash
./gradlew clean build
```

### 3. Start the Application
```bash
./gradlew bootRun
```

### 4. Verify Migration
On startup, you should see Flyway logs like:
```
Flyway Community Edition 9.x.x
Database: jdbc:postgresql://localhost:5432/detailing_crm
Successfully validated 3 migrations
Creating Schema History table [public]."flyway_schema_history" with baseline ...
Successfully baselined schema with version: 1
Current version of schema [public]: 1
Migrating schema [public] to version "2 - create visit tables"
Migrating schema [public] to version "3 - add converted status to appointments"
Successfully applied 2 migrations to schema [public]
```

### 5. Test the Endpoint
```bash
curl -X POST http://localhost:8080/api/checkin/reservation-to-visit \
  -H "Content-Type: application/json" \
  -d '{
    "reservationId": "f6e530d2-0004-4cae-b7c6-0ade33f7e749",
    "customer": {...},
    "vehicle": {...},
    "technicalState": {...},
    "photoIds": [],
    "services": [...]
  }'
```

## Troubleshooting

### If Flyway Fails to Migrate
If you see errors like "Baseline applied but migrations still fail", run the manual SQL fix:

```bash
psql -U postgres -d detailing_crm -f manual-fix-constraint.sql
```

### If Constraint Still Exists
Check current constraints:
```sql
SELECT
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint
WHERE conrelid = 'appointments'::regclass
  AND conname LIKE '%status%';
```

### Check Flyway Migration History
```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

Expected output:
```
installed_rank | version | description                      | type | script                                      | success
---------------+---------+----------------------------------+------+---------------------------------------------+---------
1              | 1       | create consent tables            | SQL  | V1__create_consent_tables.sql               | t
2              | 2       | create visit tables              | SQL  | V2__create_visit_tables.sql                 | t
3              | 3       | add converted status to appoint. | SQL  | V3__add_converted_status_to_appointments... | t
```

## Changes Made

### build.gradle.kts
- Added Flyway dependencies:
  ```kotlin
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
  ```

### application.properties
- Changed: `spring.jpa.hibernate.ddl-auto=update` → `validate`
- Added:
  ```properties
  spring.flyway.enabled=true
  spring.flyway.baseline-on-migrate=true
  spring.flyway.locations=classpath:db/migration
  ```

### New Migration
- **V3__add_converted_status_to_appointments.sql**
  - Drops old `appointments_status_check` constraint
  - Creates new constraint with `CONVERTED` status included

## Why This Happened
Previously, the application used Hibernate's `ddl-auto=update` which automatically updates the schema. However, Hibernate doesn't modify CHECK constraints, so when we added `CONVERTED` to the enum, the database constraint was not updated.

Flyway provides explicit control over schema changes through versioned migrations, which is the recommended approach for production applications.
