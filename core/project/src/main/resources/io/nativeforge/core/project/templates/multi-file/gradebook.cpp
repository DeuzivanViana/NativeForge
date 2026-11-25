#include "gradebook.hpp"

#include <algorithm>
#include <numeric>
#include <stdexcept>

void Gradebook::add(std::string name, double grade) {
    if (grade < 0.0 || grade > 10.0) throw std::invalid_argument("grades go from 0 to 10");
    students_.push_back({std::move(name), grade});
}

double Gradebook::mean() const {
    if (students_.empty()) return 0.0;
    double sum = std::accumulate(students_.begin(), students_.end(), 0.0,
                                 [](double total, const Student& s) { return total + s.grade; });
    return sum / static_cast<double>(students_.size());
}

double Gradebook::median() const {
    if (students_.empty()) return 0.0;
    std::vector<double> grades;
    for (const auto& s : students_) grades.push_back(s.grade);
    std::sort(grades.begin(), grades.end());
    const std::size_t middle = grades.size() / 2;
    return grades.size() % 2 ? grades[middle] : (grades[middle - 1] + grades[middle]) / 2.0;
}

std::vector<Student> Gradebook::ranking() const {
    std::vector<Student> sorted = students_;
    std::stable_sort(sorted.begin(), sorted.end(),
                     [](const Student& a, const Student& b) { return a.grade > b.grade; });
    return sorted;
}
