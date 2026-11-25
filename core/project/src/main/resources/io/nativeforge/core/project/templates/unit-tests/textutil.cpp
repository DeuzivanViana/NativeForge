#include "textutil.hpp"

#include <algorithm>
#include <numeric>
#include <sstream>

namespace textutil {

long gcd(long a, long b) {
    return std::gcd(a, b);
}

bool isPrime(long n) {
    if (n < 2) return false;
    for (long d = 2; d * d <= n; d++) {
        if (n % d == 0) return false;
    }
    return true;
}

std::string reversed(std::string text) {
    std::reverse(text.begin(), text.end());
    return text;
}

std::vector<std::string> words(const std::string& text) {
    std::istringstream in(text);
    std::vector<std::string> result;
    for (std::string word; in >> word;) result.push_back(word);
    return result;
}

double toFahrenheit(double celsius) {
    return celsius * 9.0 / 5.0 + 32.0;
}

} // namespace textutil
