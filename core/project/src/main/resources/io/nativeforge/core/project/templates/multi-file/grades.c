#include "grades.h"

#include <stdlib.h>
#include <string.h>

void gradebook_init(Gradebook *book)
{
    book->items = NULL;
    book->count = 0;
    book->capacity = 0;
}

void gradebook_free(Gradebook *book)
{
    free(book->items);
    gradebook_init(book);
}

int gradebook_add(Gradebook *book, const char *name, double grade)
{
    if (book->count == book->capacity) {
        size_t capacity = book->capacity ? book->capacity * 2 : 4;
        Student *items = realloc(book->items, capacity * sizeof *items);
        if (items == NULL) return -1;
        book->items = items;
        book->capacity = capacity;
    }
    Student *student = &book->items[book->count++];
    strncpy(student->name, name, NAME_SIZE - 1);
    student->name[NAME_SIZE - 1] = '\0';
    student->grade = grade;
    return 0;
}

double gradebook_mean(const Gradebook *book)
{
    if (book->count == 0) return 0.0;
    double sum = 0.0;
    for (size_t i = 0; i < book->count; i++) sum += book->items[i].grade;
    return sum / (double)book->count;
}

/* static: visible only inside this file, like a private function. */
static int by_grade_descending(const void *a, const void *b)
{
    double ga = ((const Student *)a)->grade, gb = ((const Student *)b)->grade;
    return (ga < gb) - (ga > gb);
}

void gradebook_sort(Gradebook *book)
{
    qsort(book->items, book->count, sizeof *book->items, by_grade_descending);
}

double gradebook_median(const Gradebook *book)
{
    if (book->count == 0) return 0.0;
    double *grades = malloc(book->count * sizeof *grades);
    if (grades == NULL) return 0.0;
    for (size_t i = 0; i < book->count; i++) grades[i] = book->items[i].grade;
    /* Insertion sort: fine for a class-sized list. */
    for (size_t i = 1; i < book->count; i++) {
        double value = grades[i];
        size_t j = i;
        for (; j > 0 && grades[j - 1] > value; j--) grades[j] = grades[j - 1];
        grades[j] = value;
    }
    size_t middle = book->count / 2;
    double median = book->count % 2 ? grades[middle] : (grades[middle - 1] + grades[middle]) / 2.0;
    free(grades);
    return median;
}
