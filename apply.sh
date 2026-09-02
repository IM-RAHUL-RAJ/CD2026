#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATIONS_DIR="$ROOT_DIR/migrations"
SEED_DIR="$ROOT_DIR/seed"
DOTENV_FILE="$ROOT_DIR/.env"

read_dotenv_var() {
    local key="$1"
    local file="$2"

    [[ -f "$file" ]] || return 1

    awk -v key="$key" '
        BEGIN { found = 0 }
        {
            line = $0
            sub(/\r$/, "", line)

            if (line ~ /^[[:space:]]*#/ || line ~ /^[[:space:]]*$/) {
                next
            }

            pattern = "^[[:space:]]*(export[[:space:]]+)?" key "[[:space:]]*="
            if (match(line, pattern)) {
                value = substr(line, RSTART + RLENGTH)
                sub(/^[[:space:]]+/, "", value)

                if (value ~ /^".*"$/ || value ~ /^\047.*\047$/) {
                    value = substr(value, 2, length(value) - 2)
                } else {
                    sub(/[[:space:]]+#.*$/, "", value)
                    sub(/[[:space:]]+$/, "", value)
                }

                result = value
                found = 1
            }
        }
        END {
            if (found) {
                print result
            } else {
                exit 1
            }
        }
    ' "$file"
}

resolve_target_database() {
    local target="${TARGET_DATABASE:-}"

    if [[ -n "$target" ]]; then
        printf '%s\n' "$target"
        return 0
    fi

    if target="$(read_dotenv_var "POSTGRES_DB" "$DOTENV_FILE")" && [[ -n "$target" ]]; then
        printf '%s\n' "$target"
        return 0
    fi

    echo "Error: TARGET_DATABASE is not set and POSTGRES_DB was not found in $DOTENV_FILE" >&2
    exit 1
}

apply_sql_directory() {
    local dir_path="$1"
    local phase_label="$2"
    local database_name="$3"

    if [[ ! -d "$dir_path" ]]; then
        echo "Error: Required directory not found: $dir_path" >&2
        exit 1
    fi

    mapfile -t sql_files < <(find "$dir_path" -maxdepth 1 -type f -name '*.sql' -print | sort)

    if [[ ${#sql_files[@]} -eq 0 ]]; then
        echo "Error: No .sql files found in $dir_path" >&2
        exit 1
    fi

    for sql_file in "${sql_files[@]}"; do
        echo "[$phase_label] Applying $(basename "$sql_file")"
        psql \
            --no-psqlrc \
            --no-password \
            --dbname "$database_name" \
            --file "$sql_file" \
            -v ON_ERROR_STOP=1
    done
}

TARGET_DB="$(resolve_target_database)"

echo "Using database: $TARGET_DB"
apply_sql_directory "$MIGRATIONS_DIR" "migration" "$TARGET_DB"
apply_sql_directory "$SEED_DIR" "seed" "$TARGET_DB"
echo "Database apply complete."