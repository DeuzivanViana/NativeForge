#pragma once

#include <string>
#include <vector>

// A header declares what a module offers; the matching .cpp file defines it. Every .cpp file
// in src/ is compiled on its own and the linker joins them, so main.cpp only needs this header.
// Headers in include/ are found with #include "name.hpp" from any source file.

struct Student {
    std::string name;
    double grade = 0.0;
};

class Gradebook {
public:
    void add(std::string name, double grade);

    double mean() const;
    double median() const;
    // From the highest grade to the lowest.
    std::vector<Student> ranking() const;

    bool empty() const { return students_.empty(); }
    std::size_t size() const { return students_.size(); }

private:
    std::vector<Student> students_;
};
