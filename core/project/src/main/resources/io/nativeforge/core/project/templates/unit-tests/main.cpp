#include <iostream>

#include "check.hpp"
#include "textutil.hpp"

// Unit tests for src/textutil.cpp. Run the project to see the results; the exit code is the
// number of failures, so scripts and CI can tell. Try breaking a function (for example make
// isPrime(2) return false) and run again. New test: write another TEST(name) { ... } block.

using namespace textutil;

TEST(gcd_of_pairs) {
    CHECK_EQ(6L, gcd(54, 24));
    CHECK_EQ(1L, gcd(17, 5));
    CHECK_EQ(5L, gcd(0, 5));
    CHECK_EQ(4L, gcd(-8, 12));
}

TEST(primes) {
    CHECK(isPrime(2));
    CHECK(isPrime(97));
    CHECK(!isPrime(1));
    CHECK(!isPrime(91)); // 7 x 13
}

TEST(reversing_text) {
    CHECK_EQ(std::string("egroFevitaN"), reversed("NativeForge"));
    CHECK_EQ(std::string(""), reversed(""));
}

TEST(splitting_words) {
    CHECK_EQ(std::size_t{0}, words("").size());
    auto list = words("  one two\tthree\n");
    CHECK_EQ(std::size_t{3}, list.size());
    CHECK_EQ(std::string("three"), list.back());
}

TEST(temperatures) {
    CHECK_NEAR(32.0, toFahrenheit(0.0), 1e-9);
    CHECK_NEAR(98.6, toFahrenheit(37.0), 1e-9);
}

int main() {
    std::cout << "Running the tests of @PROJECT_NAME@\n\n";
    int failedTests = 0;
    for (const auto& test : check::registry()) {
        const int before = check::counters().failures;
        test.run();
        const bool passed = check::counters().failures == before;
        if (!passed) failedTests++;
        std::cout << "  " << (passed ? "\033[32mPASS\033[0m " : "\033[31mFAIL\033[0m ") << test.name << '\n';
    }
    const auto& c = check::counters();
    std::cout << '\n' << c.checks << " checks, " << c.failures << " failed, in " << failedTests << " of "
              << check::registry().size() << " tests.\n";
    return c.failures;
}
