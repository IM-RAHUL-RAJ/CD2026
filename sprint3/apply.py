import psycopg
import os

DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "sprint-3",
    "user": os.environ["PGUSER"],
    "password": os.environ["PGPASSWORD"]
}

MIGRATION_FILES = [
    "001_clean_up_db.sql",
    "002_create_tables.sql",
    "003_create_indices.sql",
    "004_create_triggers.sql"
]

MIGRATION_PATH = "migrations"
SEED_FILE = "seed\\seed.sql"


def execute_sql_file(cursor, file_path):
    print(f"Executing {file_path}...")

    with open(file_path, "r") as file:
        sql = file.read()

    cursor.execute(sql)

    print(f"Completed {file_path}")


with psycopg.connect(**DB_CONFIG) as connection:
    with connection.cursor() as cursor:

        # Execute migrations
        for migration_file in MIGRATION_FILES:
            file_path = f"{MIGRATION_PATH}\\{migration_file}"
            execute_sql_file(cursor, file_path)

        # Execute seed data
        execute_sql_file(cursor, SEED_FILE)

    connection.commit()

print("Database setup completed successfully")