#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <sqlite3.h>

/*
 * A to-do list in the terminal, stored in todo.db with SQLite. Shows the everyday
 * SQLite calls: open a database, create a table, insert and update rows with
 * prepared statements (? placeholders, so user text is never pasted into SQL),
 * read rows one by one, and run any SQL you type with sqlite3_exec().
 * todo.db is created in the program's working folder (the project folder when run
 * from the IDE); any SQLite tool can open it.
 */

static const char *DB_FILE = "todo.db";

static const char *SCHEMA =
    "CREATE TABLE IF NOT EXISTS tasks ("
    "  id      INTEGER PRIMARY KEY,"
    "  title   TEXT NOT NULL,"
    "  done    INTEGER NOT NULL DEFAULT 0,"
    "  created TEXT NOT NULL DEFAULT (datetime('now', 'localtime')));";

/* Reads one line from the keyboard without the newline. Returns 0 at end of input. */
static int ask(const char *prompt, char *line, size_t size)
{
    printf("%s", prompt);
    fflush(stdout);
    if (!fgets(line, (int)size, stdin)) return 0;
    line[strcspn(line, "\r\n")] = '\0';
    return 1;
}

/* Prints the tasks whose title contains `filter` (all of them when it is empty). */
static void list(sqlite3 *db, const char *filter)
{
    sqlite3_stmt *stmt;
    const char *sql = "SELECT id, done, title, created FROM tasks "
                      "WHERE title LIKE '%' || ?1 || '%' ORDER BY done, id";
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) != SQLITE_OK) {
        printf("SQL error: %s\n", sqlite3_errmsg(db));
        return;
    }
    sqlite3_bind_text(stmt, 1, filter, -1, SQLITE_TRANSIENT);
    int rows = 0;
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        printf("  %3d [%c] %s  (%s)\n", sqlite3_column_int(stmt, 0), sqlite3_column_int(stmt, 1) ? 'x' : ' ',
               (const char *)sqlite3_column_text(stmt, 2), (const char *)sqlite3_column_text(stmt, 3));
        rows++;
    }
    if (rows == 0) printf("  (no tasks)\n");
    sqlite3_finalize(stmt);
}

/* Runs a statement with one text and one optional integer parameter; returns rows changed. */
static int run(sqlite3 *db, const char *sql, const char *text, int number)
{
    sqlite3_stmt *stmt;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) != SQLITE_OK) {
        printf("SQL error: %s\n", sqlite3_errmsg(db));
        return 0;
    }
    if (text) sqlite3_bind_text(stmt, 1, text, -1, SQLITE_TRANSIENT);
    else sqlite3_bind_int(stmt, 1, number);
    if (sqlite3_step(stmt) != SQLITE_DONE) printf("SQL error: %s\n", sqlite3_errmsg(db));
    sqlite3_finalize(stmt);
    return sqlite3_changes(db);
}

/* sqlite3_exec() calls this once per result row of a typed query. */
static int print_row(void *first, int columns, char **values, char **names)
{
    if (*(int *)first) {
        for (int i = 0; i < columns; i++) printf("%s%s", i ? " | " : "  ", names[i]);
        printf("\n");
        *(int *)first = 0;
    }
    for (int i = 0; i < columns; i++) printf("%s%s", i ? " | " : "  ", values[i] ? values[i] : "NULL");
    printf("\n");
    return 0;
}

int main(void)
{
    sqlite3 *db;
    if (sqlite3_open(DB_FILE, &db) != SQLITE_OK) {
        fprintf(stderr, "Cannot open %s: %s\n", DB_FILE, sqlite3_errmsg(db));
        sqlite3_close(db);
        return 1;
    }
    char *error = NULL;
    if (sqlite3_exec(db, SCHEMA, NULL, NULL, &error) != SQLITE_OK) {
        fprintf(stderr, "Cannot create the table: %s\n", error);
        sqlite3_free(error);
        sqlite3_close(db);
        return 1;
    }
    printf("@PROJECT_NAME@: to-do list in %s (SQLite %s)\n", DB_FILE, sqlite3_libversion());

    char line[512];
    for (;;) {
        printf("\n[l]ist  [a]dd  [d]one  [r]emove  [f]ind  [s]ql  [q]uit\n");
        if (!ask("> ", line, sizeof line) || line[0] == 'q') break;
        switch (line[0]) {
        case 'l':
            list(db, "");
            break;
        case 'a':
            if (ask("Task: ", line, sizeof line) && line[0])
                run(db, "INSERT INTO tasks (title) VALUES (?1)", line, 0);
            break;
        case 'd':
            if (ask("Task number: ", line, sizeof line) &&
                !run(db, "UPDATE tasks SET done = 1 - done WHERE id = ?1", NULL, atoi(line)))
                printf("No task %s.\n", line);
            break;
        case 'r':
            if (ask("Task number: ", line, sizeof line) &&
                !run(db, "DELETE FROM tasks WHERE id = ?1", NULL, atoi(line)))
                printf("No task %s.\n", line);
            break;
        case 'f':
            if (ask("Find: ", line, sizeof line)) list(db, line);
            break;
        case 's':
            /* Any SQL: try SELECT count(*), sum(done) FROM tasks; */
            if (ask("SQL> ", line, sizeof line) && line[0]) {
                int first = 1;
                if (sqlite3_exec(db, line, print_row, &first, &error) != SQLITE_OK) {
                    printf("SQL error: %s\n", error);
                    sqlite3_free(error);
                }
            }
            break;
        }
    }
    sqlite3_close(db);
    return 0;
}
