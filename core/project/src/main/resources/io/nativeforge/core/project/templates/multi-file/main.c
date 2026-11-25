#include <stdio.h>
#include <string.h>

#include "grades.h"

/*
 * A program split into modules: include/grades.h declares a small grade book,
 * src/grades.c implements it, and this file only reads input and prints the report.
 * Add a new module by creating src/name.c and include/name.h: the build picks them up.
 */

int main(void)
{
    Gradebook book;
    gradebook_init(&book);

    printf("@PROJECT_NAME@: type a name and a grade per line (for example: Ana 8.5).\n");
    printf("An empty line shows the report.\n\n");

    char line[128];
    while (printf("> "), fflush(stdout), fgets(line, sizeof line, stdin) != NULL) {
        if (line[0] == '\n') break;
        char name[NAME_SIZE];
        double grade;
        if (sscanf(line, "%31s %lf", name, &grade) != 2 || grade < 0.0 || grade > 10.0) {
            printf("  Use: name grade (0 to 10)\n");
            continue;
        }
        if (gradebook_add(&book, name, grade) != 0) {
            printf("  Out of memory\n");
            break;
        }
    }

    if (book.count == 0) {
        /* Sample data, so the report is not empty the first time. */
        gradebook_add(&book, "Ana", 9.0);
        gradebook_add(&book, "Bruno", 6.5);
        gradebook_add(&book, "Carla", 7.8);
        printf("\nNo grades typed; using a sample class.\n");
    }

    gradebook_sort(&book);
    printf("\n%-12s %s\n", "Student", "Grade");
    for (size_t i = 0; i < book.count; i++) {
        printf("%-12s %5.1f %s\n", book.items[i].name, book.items[i].grade, book.items[i].grade >= 6.0 ? "" : "(below 6)");
    }
    printf("\nStudents: %zu   Mean: %.2f   Median: %.2f\n", book.count, gradebook_mean(&book), gradebook_median(&book));

    gradebook_free(&book);
    return 0;
}
