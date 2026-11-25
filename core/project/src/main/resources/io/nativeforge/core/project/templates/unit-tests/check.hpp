#pragma once

#include <cmath>
#include <functional>
#include <iostream>
#include <string>
#include <vector>

// A tiny unit test framework: TEST(name) { ... } registers a test by itself (a static
// object runs before main), and CHECK() records a failure with its line instead of stopping,
// so one run shows every broken expectation. GoogleTest and Catch2 work the same way.

namespace check {

struct Test {
    const char* name;
    std::function<void()> run;
};

inline std::vector<Test>& registry() {
    static std::vector<Test> tests;
    return tests;
}

struct Counters {
    int checks = 0;
    int failures = 0;
};

inline Counters& counters() {
    static Counters c;
    return c;
}

struct Registrar {
    Registrar(const char* name, std::function<void()> run) { registry().push_back({name, std::move(run)}); }
};

template <typename Expected, typename Actual>
void equal(const Expected& expected, const Actual& actual, const char* expression, const char* file, int line) {
    counters().checks++;
    if (!(expected == actual)) {
        counters().failures++;
        std::cout << "    \033[31mFAILED\033[0m " << file << ':' << line << ": " << expression << " is " << actual
                  << ", expected " << expected << '\n';
    }
}

inline void that(bool condition, const char* expression, const char* file, int line) {
    counters().checks++;
    if (!condition) {
        counters().failures++;
        std::cout << "    \033[31mFAILED\033[0m " << file << ':' << line << ": " << expression << '\n';
    }
}

} // namespace check

#define CHECK_CONCAT_(a, b) a##b
#define CHECK_CONCAT(a, b) CHECK_CONCAT_(a, b)
#define TEST(name)                                                                       \
    static void name();                                                                  \
    static check::Registrar CHECK_CONCAT(name, _registrar)(#name, name);                 \
    static void name()
#define CHECK(condition) check::that((condition), #condition, __FILE__, __LINE__)
#define CHECK_EQ(expected, actual) check::equal((expected), (actual), #actual, __FILE__, __LINE__)
#define CHECK_NEAR(expected, actual, tolerance) CHECK(std::fabs((expected) - (actual)) <= (tolerance))
