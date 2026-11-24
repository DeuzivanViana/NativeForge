#include <sqlite3.h>

#include <iostream>
#include <stdexcept>
#include <string>

// A to-do list in the terminal, stored in todo.db with SQLite. Shows the everyday
// SQLite calls from C++: a small RAII wrapper closes the database and finalizes
// statements, user text goes through ? placeholders (never pasted into SQL), rows
// are read one by one, and any SQL you type runs with sqlite3_exec().
// todo.db is created in the program's working folder (the project folder when run
// from the IDE); any SQLite tool can open it.

namespace {

const char* kDbFile = "todo.db";

const char* kSchema =
    "CREATE TABLE IF NOT EXISTS tasks ("
    "  id      INTEGER PRIMARY KEY,"
    "  title   TEXT NOT NULL,"
    "  done    INTEGER NOT NULL DEFAULT 0,"
    "  created TEXT NOT NULL DEFAULT (datetime('now', 'localtime')));";

// A prepared statement that finalizes itself.
class Statement {
public:
    Statement(sqlite3* db, const char* sql) : db_(db) {
        if (sqlite3_prepare_v2(db, sql, -1, &stmt_, nullptr) != SQLITE_OK)
            throw std::runtime_error(sqlite3_errmsg(db));
    }
    ~Statement() { sqlite3_finalize(stmt_); }
    Statement(const Statement&) = delete;
    Statement& operator=(const Statement&) = delete;

    Statement& bind(int index, const std::string& text) {
        sqlite3_bind_text(stmt_, index, text.c_str(), -1, SQLITE_TRANSIENT);
        return *this;
    }
    Statement& bind(int index, int number) {
        sqlite3_bind_int(stmt_, index, number);
        return *this;
    }
    // Moves to the next row; false when there are no more.
    bool step() {
        const int rc = sqlite3_step(stmt_);
        if (rc != SQLITE_ROW && rc != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(db_));
        return rc == SQLITE_ROW;
    }
    int integer(int column) const { return sqlite3_column_int(stmt_, column); }
    std::string text(int column) const {
        const unsigned char* value = sqlite3_column_text(stmt_, column);
        return value ? reinterpret_cast<const char*>(value) : "";
    }

private:
    sqlite3* db_;
    sqlite3_stmt* stmt_ = nullptr;
};

// The database connection, closed when it goes out of scope.
class Database {
public:
    explicit Database(const char* file) {
        if (sqlite3_open(file, &db_) != SQLITE_OK) {
            std::string message = sqlite3_errmsg(db_);
            sqlite3_close(db_);
            throw std::runtime_error("cannot open " + std::string(file) + ": " + message);
        }
    }
    ~Database() { sqlite3_close(db_); }
    Database(const Database&) = delete;
    Database& operator=(const Database&) = delete;

    sqlite3* handle() const { return db_; }
    int changes() const { return sqlite3_changes(db_); }

    // Runs SQL without parameters; calls onRow for each result row, if any.
    template <typename OnRow>
    void exec(const std::string& sql, OnRow onRow) {
        char* error = nullptr;
        auto callback = [](void* user, int columns, char** values, char** names) {
            (*static_cast<OnRow*>(user))(columns, values, names);
            return 0;
        };
        if (sqlite3_exec(db_, sql.c_str(), callback, &onRow, &error) != SQLITE_OK) {
            std::string message = error ? error : "unknown error";
            sqlite3_free(error);
            throw std::runtime_error(message);
        }
    }

private:
    sqlite3* db_ = nullptr;
};

std::string ask(const std::string& prompt) {
    std::cout << prompt << std::flush;
    std::string line;
    std::getline(std::cin, line);
    return line;
}

int toNumber(const std::string& text) {
    try {
        return std::stoi(text);
    } catch (const std::exception&) {
        return 0;
    }
}

// Prints the tasks whose title contains filter (all of them when it is empty).
void list(Database& db, const std::string& filter) {
    Statement query(db.handle(), "SELECT id, done, title, created FROM tasks "
                                 "WHERE title LIKE '%' || ?1 || '%' ORDER BY done, id");
    query.bind(1, filter);
    int rows = 0;
    for (; query.step(); ++rows) {
        std::cout << "  " << query.integer(0) << " [" << (query.integer(1) ? 'x' : ' ') << "] "
                  << query.text(2) << "  (" << query.text(3) << ")\n";
    }
    if (rows == 0) std::cout << "  (no tasks)\n";
}

// Runs an UPDATE or DELETE on one task; tells the user when the number does not exist.
void changeTask(Database& db, const char* sql) {
    const std::string number = ask("Task number: ");
    Statement(db.handle(), sql).bind(1, toNumber(number)).step();
    if (db.changes() == 0) std::cout << "No task " << number << ".\n";
}

} // namespace

int main() {
    try {
        Database db(kDbFile);
        db.exec(kSchema, [](int, char**, char**) {});
        std::cout << "@PROJECT_NAME@: to-do list in " << kDbFile << " (SQLite " << sqlite3_libversion() << ")\n";

        for (;;) {
            std::cout << "\n[l]ist  [a]dd  [d]one  [r]emove  [f]ind  [s]ql  [q]uit\n";
            const std::string choice = ask("> ");
            if (!std::cin || choice == "q") break;
            try {
                if (choice == "l") {
                    list(db, "");
                } else if (choice == "a") {
                    const std::string title = ask("Task: ");
                    if (!title.empty()) Statement(db.handle(), "INSERT INTO tasks (title) VALUES (?1)").bind(1, title).step();
                } else if (choice == "d") {
                    changeTask(db, "UPDATE tasks SET done = 1 - done WHERE id = ?1");
                } else if (choice == "r") {
                    changeTask(db, "DELETE FROM tasks WHERE id = ?1");
                } else if (choice == "f") {
                    list(db, ask("Find: "));
                } else if (choice == "s") {
                    // Any SQL: try SELECT count(*), sum(done) FROM tasks;
                    const std::string sql = ask("SQL> ");
                    bool first = true;
                    if (!sql.empty()) {
                        db.exec(sql, [&first](int columns, char** values, char** names) {
                            if (first) {
                                for (int i = 0; i < columns; ++i) std::cout << (i ? " | " : "  ") << names[i];
                                std::cout << "\n";
                                first = false;
                            }
                            for (int i = 0; i < columns; ++i) std::cout << (i ? " | " : "  ") << (values[i] ? values[i] : "NULL");
                            std::cout << "\n";
                        });
                    }
                }
            } catch (const std::exception& e) {
                std::cout << "SQL error: " << e.what() << "\n";
            }
        }
    } catch (const std::exception& e) {
        std::cerr << e.what() << "\n";
        return 1;
    }
    return 0;
}
