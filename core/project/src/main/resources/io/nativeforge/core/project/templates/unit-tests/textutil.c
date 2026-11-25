#include "textutil.h"

#include <ctype.h>
#include <string.h>

long gcd(long a, long b)
{
    if (a < 0) a = -a;
    if (b < 0) b = -b;
    while (b != 0) {
        long rest = a % b;
        a = b;
        b = rest;
    }
    return a;
}

bool is_prime(long n)
{
    if (n < 2) return false;
    for (long d = 2; d * d <= n; d++) {
        if (n % d == 0) return false;
    }
    return true;
}

char *reverse(char *text)
{
    size_t length = strlen(text);
    for (size_t i = 0; i < length / 2; i++) {
        char c = text[i];
        text[i] = text[length - 1 - i];
        text[length - 1 - i] = c;
    }
    return text;
}

size_t word_count(const char *text)
{
    size_t words = 0;
    bool in_word = false;
    for (; *text; text++) {
        if (isspace((unsigned char)*text)) {
            in_word = false;
        } else if (!in_word) {
            in_word = true;
            words++;
        }
    }
    return words;
}

double to_fahrenheit(double celsius)
{
    return celsius * 9.0 / 5.0 + 32.0;
}
