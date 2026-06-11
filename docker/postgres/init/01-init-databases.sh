#!/bin/bash
# Runs once on first cluster init (postgres docker-entrypoint sources *.sh here).
# Creates the two application databases in a SINGLE pgvector container:
#   - dthcrm      : the dth-crm host's main CRM store
#   - agentstore  : the ai-agent add-on store (+ Spring AI pgvector table)
# pgvector is enabled in agentstore (the add-on's vector store lives there).
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-'EOSQL'
	CREATE DATABASE "dthcrm";
	CREATE DATABASE "agentstore";
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "agentstore" <<-'EOSQL'
	CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
