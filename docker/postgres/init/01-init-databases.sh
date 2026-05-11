#!/bin/bash
# Runs once on first cluster init (postgres docker-entrypoint sources *.sh here).
# Creates the two application databases and enables pgvector in agentstore.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-'EOSQL'
	CREATE DATABASE "ai-agent";
	CREATE DATABASE "agentstore";
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "agentstore" <<-'EOSQL'
	CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
