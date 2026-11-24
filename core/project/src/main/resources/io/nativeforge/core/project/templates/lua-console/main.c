#include <stdio.h>
#include <string.h>
#include <time.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

/*
 * Embeds the Lua 5.4 interpreter in a C program: the program gives Lua a few functions
 * written in C (the "app" table), runs a script, calls a Lua function from C, and then
 * opens an interactive prompt in the terminal. scripts/startup.lua is run when present,
 * so you can change the program's behaviour without recompiling.
 */

/* app.clock() -> seconds since the program started, as a number. */
static int app_clock(lua_State *L)
{
    lua_pushnumber(L, (lua_Number)clock() / CLOCKS_PER_SEC);
    return 1;
}

/* app.repeat(text, n) -> text repeated n times; shows how C reads arguments and checks types. */
static int app_repeat(lua_State *L)
{
    size_t length = 0;
    const char *text = luaL_checklstring(L, 1, &length);
    lua_Integer count = luaL_checkinteger(L, 2);
    luaL_argcheck(L, count >= 0 && count <= 1000, 2, "count must be between 0 and 1000");
    luaL_Buffer buffer;
    luaL_buffinit(L, &buffer);
    for (lua_Integer i = 0; i < count; i++) luaL_addlstring(&buffer, text, length);
    luaL_pushresult(&buffer);
    return 1;
}

static const luaL_Reg app_functions[] = {
    {"clock", app_clock},
    {"repeat_text", app_repeat},
    {NULL, NULL},
};

static const char *DEMO_SCRIPT =
    "-- Lua code embedded in main.c\n"
    "function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end\n"
    "local squares = {}\n"
    "for i = 1, 8 do squares[#squares + 1] = i * i end\n"
    "print('Squares: ' .. table.concat(squares, ', '))\n"
    "print(app.repeat_text('=-', 20))\n";

/* Runs a chunk and prints its error, if any. Returns 1 on success. */
static int report(lua_State *L, int status)
{
    if (status == LUA_OK) return 1;
    const char *message = lua_tostring(L, -1);
    fprintf(stderr, "error: %s\n", message ? message : "(no message)");
    lua_pop(L, 1);
    return 0;
}

/* Prints every value a chunk returned, like the standalone lua prompt does. */
static void print_results(lua_State *L, int base)
{
    int count = lua_gettop(L) - base;
    for (int i = 1; i <= count; i++) {
        printf(i > 1 ? "\t%s" : "%s", luaL_tolstring(L, base + i, NULL));
        lua_pop(L, 1); /* the string luaL_tolstring pushed */
    }
    if (count > 0) printf("\n");
    lua_settop(L, base);
}

static void run_line(lua_State *L, const char *line)
{
    int base = lua_gettop(L);
    /* First try the line as an expression ("1 + 2"), then as a statement ("x = 3"). */
    char expression[1100];
    snprintf(expression, sizeof expression, "return %s", line);
    int status = luaL_loadbuffer(L, expression, strlen(expression), "=prompt");
    if (status != LUA_OK) {
        lua_pop(L, 1);
        status = luaL_loadbuffer(L, line, strlen(line), "=prompt");
    }
    if (status == LUA_OK) status = lua_pcall(L, 0, LUA_MULTRET, 0);
    if (report(L, status)) print_results(L, base);
}

int main(void)
{
    lua_State *L = luaL_newstate();
    luaL_openlibs(L);
    luaL_newlib(L, app_functions);
    lua_setglobal(L, "app");

    printf("%s embedded in @PROJECT_NAME@\n\n", LUA_RELEASE);
    report(L, luaL_dostring(L, DEMO_SCRIPT));

    /* Call the Lua function fib() from C. */
    lua_getglobal(L, "fib");
    lua_pushinteger(L, 20);
    if (report(L, lua_pcall(L, 1, 1, 0))) {
        printf("fib(20) computed by Lua = %lld\n", (long long)lua_tointeger(L, -1));
        lua_pop(L, 1);
    }

    FILE *startup = fopen("scripts/startup.lua", "r");
    if (startup != NULL) {
        fclose(startup);
        printf("Running scripts/startup.lua\n");
        report(L, luaL_dofile(L, "scripts/startup.lua"));
    }

    printf("\nType Lua code, for example: fib(25), app.clock(), math.sqrt(2)\n");
    printf("Type 'exit' to quit.\n");
    char line[1024];
    for (;;) {
        printf("> ");
        fflush(stdout);
        if (fgets(line, sizeof line, stdin) == NULL) break;
        line[strcspn(line, "\r\n")] = '\0';
        if (strcmp(line, "exit") == 0) break;
        if (line[0] != '\0') run_line(L, line);
    }
    lua_close(L);
    return 0;
}
