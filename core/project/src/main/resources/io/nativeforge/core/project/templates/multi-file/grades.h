#ifndef GRADES_H
#define GRADES_H

#include <stddef.h>

/*
 * A header declares what a module offers; the matching .c file defines it. Every .c file in
 * src/ is compiled on its own and the linker joins them, so main.c only needs this header.
 * Headers in include/ are found with #include "name.h" from any source file.
 */

#define NAME_SIZE 32

typedef struct {
    char name[NAME_SIZE];
    double grade;
} Student;

typedef struct {
    Student *items; /* grows with realloc() as students are added */
    size_t count;
    size_t capacity;
} Gradebook;

void gradebook_init(Gradebook *book);
void gradebook_free(Gradebook *book);
/* Returns 0 on success, -1 when memory runs out. */
int gradebook_add(Gradebook *book, const char *name, double grade);

double gradebook_mean(const Gradebook *book);
double gradebook_median(const Gradebook *book);
/* Sorts from the highest grade to the lowest. */
void gradebook_sort(Gradebook *book);

#endif /* GRADES_H */
