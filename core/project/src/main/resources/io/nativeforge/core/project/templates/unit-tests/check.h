#ifndef CHECK_H
#define CHECK_H

#include <math.h>
#include <stdio.h>
#include <string.h>

/*
 * A tiny unit test helper: CHECK() records a failure with its file and line instead of
 * stopping, so one run shows every broken expectation. Bigger projects use a library
 * (Unity, CMocka, GoogleTest for C++); the idea is the same.
 */

extern int check_failures;
extern int check_count;

#define CHECK(condition)                                                                   \
    do {                                                                                   \
        check_count++;                                                                     \
        if (!(condition)) {                                                                \
            check_failures++;                                                              \
            printf("    \033[31mFAILED\033[0m %s:%d: %s\n", __FILE__, __LINE__, #condition); \
        }                                                                                  \
    } while (0)

#define CHECK_EQ_INT(expected, actual)                                                         \
    do {                                                                                       \
        long long check_e_ = (expected), check_a_ = (actual);                                  \
        check_count++;                                                                         \
        if (check_e_ != check_a_) {                                                            \
            check_failures++;                                                                  \
            printf("    \033[31mFAILED\033[0m %s:%d: %s is %lld, expected %lld\n", __FILE__, __LINE__, \
                   #actual, check_a_, check_e_);                                               \
        }                                                                                      \
    } while (0)

#define CHECK_EQ_STR(expected, actual)                                                         \
    do {                                                                                       \
        const char *check_e_ = (expected), *check_a_ = (actual);                               \
        check_count++;                                                                         \
        if (strcmp(check_e_, check_a_) != 0) {                                                 \
            check_failures++;                                                                  \
            printf("    \033[31mFAILED\033[0m %s:%d: %s is \"%s\", expected \"%s\"\n", __FILE__, \
                   __LINE__, #actual, check_a_, check_e_);                                     \
        }                                                                                      \
    } while (0)

#define CHECK_NEAR(expected, actual, tolerance) CHECK(fabs((double)(expected) - (double)(actual)) <= (tolerance))

#endif /* CHECK_H */
