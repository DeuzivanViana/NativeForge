#include <cstdio>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

#include "gradebook.hpp"

// A program split into modules: include/gradebook.hpp declares a small grade book class,
// src/gradebook.cpp implements it, and this file only reads input and prints the report.
// Add a new module by creating src/name.cpp and include/name.hpp: the build picks them up.

int main() {
    Gradebook book;
    std::cout << "@PROJECT_NAME@: type a name and a grade per line (for example: Ana 8.5).\n"
              << "An empty line shows the report.\n\n";

    std::string line;
    while (std::cout << "> " << std::flush && std::getline(std::cin, line) && !line.empty()) {
        std::istringstream in(line);
        std::string name;
        double grade = 0.0;
        if (!(in >> name >> grade)) {
            std::cout << "  Use: name grade (0 to 10)\n";
            continue;
        }
        try {
            book.add(name, grade);
        } catch (const std::invalid_argument& error) {
            std::cout << "  " << error.what() << '\n';
        }
    }

    if (book.empty()) {
        // Sample data, so the report is not empty the first time.
        book.add("Ana", 9.0);
        book.add("Bruno", 6.5);
        book.add("Carla", 7.8);
        std::cout << "\nNo grades typed; using a sample class.\n";
    }

    std::printf("\n%-12s %s\n", "Student", "Grade");
    for (const auto& s : book.ranking()) {
        std::printf("%-12s %5.1f %s\n", s.name.c_str(), s.grade, s.grade >= 6.0 ? "" : "(below 6)");
    }
    std::printf("\nStudents: %zu   Mean: %.2f   Median: %.2f\n", book.size(), book.mean(), book.median());
    return 0;
}
