#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

// A contact book in the terminal that keeps its data in contacts.json, using
// JSON for Modern C++ (nlohmann/json). Structs convert to and from JSON through
// to_json/from_json, so saving and loading are one line each.
// The file is written next to the program's working folder (the project folder
// when run from the IDE): open it in the editor to see the result.

using nlohmann::json;

struct Contact {
    std::string name;
    std::string phone;
    std::string email;
    std::vector<std::string> tags;
};

// Lets json j = contact; and contact = j.get<Contact>(); work.
NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(Contact, name, phone, email, tags)

namespace {

const char* kFile = "contacts.json";

std::vector<Contact> load() {
    std::ifstream in(kFile);
    if (!in) return {};
    try {
        return json::parse(in).at("contacts").get<std::vector<Contact>>();
    } catch (const json::exception& e) {
        std::cout << "Could not read " << kFile << ": " << e.what() << "\n";
        return {};
    }
}

void save(const std::vector<Contact>& contacts) {
    std::ofstream out(kFile);
    out << json{{"version", 1}, {"contacts", contacts}}.dump(2) << "\n";
}

std::string ask(const std::string& prompt) {
    std::cout << prompt;
    std::string line;
    std::getline(std::cin, line);
    return line;
}

std::string lower(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return std::tolower(c); });
    return text;
}

void print(const Contact& c, std::size_t index) {
    std::cout << "  " << index + 1 << ". " << c.name;
    if (!c.phone.empty()) std::cout << "  tel " << c.phone;
    if (!c.email.empty()) std::cout << "  " << c.email;
    if (!c.tags.empty()) {
        std::cout << "  [";
        for (std::size_t i = 0; i < c.tags.size(); ++i) std::cout << (i ? ", " : "") << c.tags[i];
        std::cout << "]";
    }
    std::cout << "\n";
}

std::vector<std::string> splitTags(const std::string& text) {
    std::vector<std::string> tags;
    std::string current;
    for (char ch : text + ",") {
        if (ch == ',') {
            current.erase(0, current.find_first_not_of(' '));
            current.erase(current.find_last_not_of(' ') + 1);
            if (!current.empty()) tags.push_back(current);
            current.clear();
        } else {
            current += ch;
        }
    }
    return tags;
}

} // namespace

int main() {
    std::vector<Contact> contacts = load();
    if (contacts.empty()) {
        contacts = {{"Ada Lovelace", "+44 20 0000 0000", "ada@example.com", {"math", "history"}},
                    {"Linus Torvalds", "", "linus@example.com", {"kernel"}}};
        save(contacts);
    }
    std::cout << "@PROJECT_NAME@: " << contacts.size() << " contacts in " << kFile << "\n";

    for (;;) {
        std::cout << "\n[l]ist  [a]dd  [s]earch  [d]elete  [j]son  [q]uit\n";
        const std::string choice = lower(ask("> "));
        if (!std::cin || choice == "q") break;
        if (choice == "l") {
            for (std::size_t i = 0; i < contacts.size(); ++i) print(contacts[i], i);
            if (contacts.empty()) std::cout << "  (no contacts)\n";
        } else if (choice == "a") {
            Contact c;
            c.name = ask("Name: ");
            if (c.name.empty()) continue;
            c.phone = ask("Phone: ");
            c.email = ask("E-mail: ");
            c.tags = splitTags(ask("Tags (comma separated): "));
            contacts.push_back(c);
            std::sort(contacts.begin(), contacts.end(),
                      [](const Contact& a, const Contact& b) { return lower(a.name) < lower(b.name); });
            save(contacts);
            std::cout << "Saved.\n";
        } else if (choice == "s") {
            const std::string term = lower(ask("Search for: "));
            for (std::size_t i = 0; i < contacts.size(); ++i) {
                const Contact& c = contacts[i];
                bool match = lower(c.name).find(term) != std::string::npos || lower(c.email).find(term) != std::string::npos;
                for (const auto& tag : c.tags) match = match || lower(tag) == term;
                if (match) print(c, i);
            }
        } else if (choice == "d") {
            const std::string number = ask("Number to delete: ");
            const std::size_t index = std::strtoul(number.c_str(), nullptr, 10);
            if (index >= 1 && index <= contacts.size()) {
                std::cout << "Deleted " << contacts[index - 1].name << ".\n";
                contacts.erase(contacts.begin() + static_cast<long>(index - 1));
                save(contacts);
            } else {
                std::cout << "No contact number " << number << ".\n";
            }
        } else if (choice == "j") {
            std::cout << json(contacts).dump(2) << "\n";
        }
    }
    return 0;
}
