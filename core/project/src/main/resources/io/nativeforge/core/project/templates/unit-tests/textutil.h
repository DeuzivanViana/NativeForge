#ifndef TEXTUTIL_H
#define TEXTUTIL_H

#include <stdbool.h>
#include <stddef.h>

/* The code under test: small functions with clear inputs and outputs. */

/* Greatest common divisor (always >= 0). */
long gcd(long a, long b);
bool is_prime(long n);
/* Reverses text in place and returns it. */
char *reverse(char *text);
/* Counts the words separated by spaces, tabs or new lines. */
size_t word_count(const char *text);
/* Celsius to Fahrenheit. */
double to_fahrenheit(double celsius);

#endif /* TEXTUTIL_H */
