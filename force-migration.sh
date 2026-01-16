#!/bin/bash
# Script to force Flyway migration

echo "Checking Flyway migration status..."
./gradlew flywayInfo

echo ""
echo "Running Flyway migration..."
./gradlew flywayMigrate

echo ""
echo "Migration completed. Checking status again..."
./gradlew flywayInfo
