#include <stdio.h>

#include "check.h"
#include "textutil.h"

/*
 * Unit tests for src/textutil.c. Run the project to see the results; the exit code is the
 * number of failures, so scripts and CI can tell. Try breaking a function (for example make
 * is_prime(2) return false) and run again. New test: write a function, add it to TESTS.
 */

int check_failures;
int check_count;

static void test_gcd(void)
{
    CHECK_EQ_INT(6, gcd(54, 24));
    CHECK_EQ_INT(1, gcd(17, 5));
    CHECK_EQ_INT(5, gcd(0, 5));
    CHECK_EQ_INT(4, gcd(-8, 12));
}

static void test_is_prime(void)
{
    CHECK(is_prime(2));
    CHECK(is_prime(97));
    CHECK(!is_prime(1));
    CHECK(!is_prime(91)); /* 7 x 13 */
}

static void test_reverse(void)
{
    char word[] = "NativeForge";
    CHECK_EQ_STR("egroFevitaN", reverse(word));
    char empty[] = "";
    CHECK_EQ_STR("", reverse(empty));
}

static void test_word_count(void)
{
    CHECK_EQ_INT(0, word_count(""));
    CHECK_EQ_INT(3, word_count("  one two\tthree\n"));
}

static void test_to_fahrenheit(void)
{
    CHECK_NEAR(32.0, to_fahrenheit(0.0), 1e-9);
    CHECK_NEAR(98.6, to_fahrenheit(37.0), 1e-9);
}

static const struct {
    const char *name;
    void (*run)(void);
} TESTS[] = {
    { "gcd", test_gcd },
    { "is_prime", test_is_prime },
    { "reverse", test_reverse },
    { "word_count", test_word_count },
    { "to_fahrenheit", test_to_fahrenheit },
};

int main(void)
{
    printf("Running the tests of @PROJECT_NAME@\n\n");
    int failed_tests = 0;
    for (size_t i = 0; i < sizeof TESTS / sizeof TESTS[0]; i++) {
        int before = check_failures;
        TESTS[i].run();
        bool passed = check_failures == before;
        if (!passed) failed_tests++;
        printf("  %s %s\n", passed ? "\033[32mPASS\033[0m" : "\033[31mFAIL\033[0m", TESTS[i].name);
    }
    printf("\n%d checks, %d failed, in %d of %zu tests.\n", check_count, check_failures, failed_tests,
           sizeof TESTS / sizeof TESTS[0]);
    return check_failures;
}
