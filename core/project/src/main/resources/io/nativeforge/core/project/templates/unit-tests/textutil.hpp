#pragma once

#include <cstddef>
#include <string>
#include <vector>

// The code under test: small functions with clear inputs and outputs.
namespace textutil {

long gcd(long a, long b);
bool isPrime(long n);
std::string reversed(std::string text);
std::vector<std::string> words(const std::string& text);
double toFahrenheit(double celsius);

} // namespace textutil
