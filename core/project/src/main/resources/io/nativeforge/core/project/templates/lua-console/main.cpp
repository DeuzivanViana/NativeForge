#include <chrono>
#include <fstream>
#include <iostream>
#include <string>

#include "lua.hpp" // lua.h, lauxlib.h and lualib.h inside extern "C"

/*
 * Embeds the Lua 5.4 interpreter in a C++ program: the program gives Lua a few functions
 * written in C++ (the "app" table), runs a script, calls a Lua function from C++, and then
 * opens an interactive prompt in the terminal. scripts/startup.lua is run when present,
 * so you can change the program's behaviour without recompiling.
 *
 * Lua is a C library. It reports errors with longjmp, which skips C++ destructors, so the
 * functions that Lua calls only use plain values (no std::string or other objects with
 * destructors) while they call luaL_check...() or lua_error().
 */

namespace {

const auto startTime = std::chrono::steady_clock::now();

/* app.clock() -> seconds since the program started, as a number. */
int appClock(lua_State *L)
{
    const std::chrono::duration<double> elapsed = std::chrono::steady_clock::now() - startTime;
    lua_pushnumber(L, elapsed.count());
    return 1;
}

/* app.repeat_text(text, n) -> text repeated n times; shows how C++ reads arguments and checks types. */
int appRepeat(lua_State *L)
{
    size_t length = 0;
    const char *text = luaL_checklstring(L, 1, &length);
    const lua_Integer count = luaL_checkinteger(L, 2);
    luaL_argcheck(L, count >= 0 && count <= 1000, 2, "count must be between 0 and 1000");
    luaL_Buffer buffer;
    luaL_buffinit(L, &buffer);
    for (lua_Integer i = 0; i < count; i++) luaL_addlstring(&buffer, text, length);
    luaL_pushresult(&buffer);
    return 1;
}

const luaL_Reg appFunctions[] = {
    {"clock", appClock},
    {"repeat_text", appRepeat},
    {nullptr, nullptr},
};

const char *const DEMO_SCRIPT = R"lua(
-- Lua code embedded in main.cpp
function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end
local squares = {}
for i = 1, 8 do squares[#squares + 1] = i * i end
print('Squares: ' .. table.concat(squares, ', '))
print(app.repeat_text('=-', 20))
)lua";

/* Owns a Lua state and closes it when it goes out of scope. */
class Lua {
public:
    Lua() : L_(luaL_newstate()) { luaL_openlibs(L_); }
    ~Lua() { lua_close(L_); }
    Lua(const Lua &) = delete;
    Lua &operator=(const Lua &) = delete;
    operator lua_State *() const { return L_; }

    /* Prints the error of a failed call, if any. Returns true on success. */
    bool report(int status) const
    {
        if (status == LUA_OK) return true;
        const char *message = lua_tostring(L_, -1);
        std::cerr << "error: " << (message ? message : "(no message)") << '\n';
        lua_pop(L_, 1);
        return false;
    }

    /* Runs a line typed at the prompt and prints what it returns, like the standalone lua prompt. */
    void runLine(const std::string &line) const
    {
        const int base = lua_gettop(L_);
        // First try the line as an expression ("1 + 2"), then as a statement ("x = 3").
        const std::string expression = "return " + line;
        int status = luaL_loadbuffer(L_, expression.data(), expression.size(), "=prompt");
        if (status != LUA_OK) {
            lua_pop(L_, 1);
            status = luaL_loadbuffer(L_, line.data(), line.size(), "=prompt");
        }
        if (status == LUA_OK) status = lua_pcall(L_, 0, LUA_MULTRET, 0);
        if (!report(status)) return;
        const int count = lua_gettop(L_) - base;
        for (int i = 1; i <= count; i++) {
            std::cout << (i > 1 ? "\t" : "") << luaL_tolstring(L_, base + i, nullptr);
            lua_pop(L_, 1); // the string luaL_tolstring pushed
        }
        if (count > 0) std::cout << '\n';
        lua_settop(L_, base);
    }

private:
    lua_State *L_;
};

} // namespace

int main()
{
    Lua lua;
    luaL_newlib(lua, appFunctions);
    lua_setglobal(lua, "app");

    std::cout << LUA_RELEASE << " embedded in @PROJECT_NAME@\n\n";
    lua.report(luaL_dostring(lua, DEMO_SCRIPT));

    // Call the Lua function fib() from C++.
    lua_getglobal(lua, "fib");
    lua_pushinteger(lua, 20);
    if (lua.report(lua_pcall(lua, 1, 1, 0))) {
        std::cout << "fib(20) computed by Lua = " << lua_tointeger(lua, -1) << '\n';
        lua_pop(lua, 1);
    }

    if (std::ifstream("scripts/startup.lua")) {
        std::cout << "Running scripts/startup.lua\n";
        lua.report(luaL_dofile(lua, "scripts/startup.lua"));
    }

    std::cout << "\nType Lua code, for example: fib(25), app.clock(), math.sqrt(2)\n"
                 "Type 'exit' to quit.\n";
    std::string line;
    while (std::cout << "> " << std::flush, std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line == "exit") break;
        if (!line.empty()) lua.runLine(line);
    }
    return 0;
}
