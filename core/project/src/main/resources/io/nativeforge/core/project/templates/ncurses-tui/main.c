#include <locale.h>
#include <ncurses.h>
#include <string.h>
#include <time.h>

/*
 * A text user interface with ncurses: a menu on the left, a panel on the right and a status
 * bar. Use the arrow keys (they are on the bar above the keyboard) or tap a menu entry.
 * ncurses draws into windows in memory; refresh() sends only what changed to the terminal.
 * Documentation: man pages at https://invisible-island.net/ncurses/man/
 */

enum { PAIR_TITLE = 1, PAIR_SELECTED, PAIR_STATUS, PAIR_ACCENT };

static const char *const ITEMS[] = { "Counter", "Colors", "Key tester", "About", "Quit" };
#define ITEM_COUNT ((int)(sizeof ITEMS / sizeof ITEMS[0]))
#define MENU_WIDTH 16

static int counter;
static int last_key = -1;

static void draw_title(void)
{
    attron(COLOR_PAIR(PAIR_TITLE) | A_BOLD);
    mvhline(0, 0, ' ', COLS);
    mvprintw(0, 1, "@PROJECT_NAME@");
    time_t now = time(NULL);
    char clock[16];
    strftime(clock, sizeof clock, "%H:%M:%S", localtime(&now));
    mvprintw(0, COLS - (int)strlen(clock) - 1, "%s", clock);
    attroff(COLOR_PAIR(PAIR_TITLE) | A_BOLD);
}

static void draw_menu(WINDOW *menu, int selected)
{
    werase(menu);
    box(menu, 0, 0);
    mvwprintw(menu, 0, 2, " Menu ");
    for (int i = 0; i < ITEM_COUNT; i++) {
        if (i == selected) wattron(menu, COLOR_PAIR(PAIR_SELECTED) | A_BOLD);
        mvwprintw(menu, i + 1, 1, " %-*s", MENU_WIDTH - 3, ITEMS[i]);
        if (i == selected) wattroff(menu, COLOR_PAIR(PAIR_SELECTED) | A_BOLD);
    }
    wnoutrefresh(menu);
}

static void draw_panel(WINDOW *panel, int selected)
{
    werase(panel);
    box(panel, 0, 0);
    mvwprintw(panel, 0, 2, " %s ", ITEMS[selected]);
    switch (selected) {
    case 0:
        mvwprintw(panel, 2, 2, "Value: ");
        wattron(panel, COLOR_PAIR(PAIR_ACCENT) | A_BOLD);
        wprintw(panel, "%d", counter);
        wattroff(panel, COLOR_PAIR(PAIR_ACCENT) | A_BOLD);
        mvwprintw(panel, 4, 2, "+ or Enter: add one");
        mvwprintw(panel, 5, 2, "- : subtract one");
        break;
    case 1:
        for (int color = 0; color < 8; color++) {
            init_pair(10 + color, COLOR_BLACK, color);
            wattron(panel, COLOR_PAIR(10 + color));
            mvwprintw(panel, 2 + color, 2, "  color %d  ", color);
            wattroff(panel, COLOR_PAIR(10 + color));
        }
        mvwprintw(panel, 11, 2, "This terminal has %d colors", COLORS);
        break;
    case 2:
        mvwprintw(panel, 2, 2, "Press any key.");
        if (last_key >= 0) mvwprintw(panel, 4, 2, "Code %d: %s", last_key, keyname(last_key));
        break;
    case 3:
        mvwprintw(panel, 2, 2, "Built with %s", curses_version());
        mvwprintw(panel, 3, 2, "Screen: %d x %d characters", COLS, LINES);
        break;
    default:
        mvwprintw(panel, 2, 2, "Enter or q quits.");
        break;
    }
    wnoutrefresh(panel);
}

static void draw_status(void)
{
    attron(COLOR_PAIR(PAIR_STATUS));
    mvhline(LINES - 1, 0, ' ', COLS);
    mvprintw(LINES - 1, 1, "Up/Down: choose   Enter: act   q: quit");
    attroff(COLOR_PAIR(PAIR_STATUS));
}

int main(void)
{
    setlocale(LC_ALL, ""); /* UTF-8 box lines and text */
    initscr();
    cbreak();              /* keys arrive at once, without Enter */
    noecho();
    curs_set(0);
    keypad(stdscr, TRUE);  /* arrow keys as KEY_UP, KEY_DOWN... */
    mousemask(BUTTON1_CLICKED | BUTTON1_PRESSED, NULL);
    timeout(1000);         /* getch() returns ERR after a second, so the clock ticks */
    if (has_colors()) {
        start_color();
        use_default_colors();
        init_pair(PAIR_TITLE, COLOR_BLACK, COLOR_CYAN);
        init_pair(PAIR_SELECTED, COLOR_BLACK, COLOR_GREEN);
        init_pair(PAIR_STATUS, COLOR_WHITE, COLOR_BLUE);
        init_pair(PAIR_ACCENT, COLOR_YELLOW, -1);
    }

    WINDOW *menu = NULL, *panel = NULL;
    int selected = 0;
    bool running = true;
    int lines = 0, cols = 0;
    while (running) {
        if (lines != LINES || cols != COLS) { /* first frame, or the screen was rotated */
            lines = LINES;
            cols = COLS;
            if (menu) delwin(menu);
            if (panel) delwin(panel);
            menu = newwin(LINES - 2, MENU_WIDTH, 1, 0);
            panel = newwin(LINES - 2, COLS - MENU_WIDTH, 1, MENU_WIDTH);
            clear();
        }
        draw_title();
        draw_status();
        wnoutrefresh(stdscr);
        draw_menu(menu, selected);
        draw_panel(panel, selected);
        doupdate();

        int key = getch();
        if (key == ERR) continue;
        if (selected == 2 && key != KEY_MOUSE) last_key = key;
        MEVENT mouse;
        switch (key) {
        case KEY_UP: selected = (selected + ITEM_COUNT - 1) % ITEM_COUNT; break;
        case KEY_DOWN: selected = (selected + 1) % ITEM_COUNT; break;
        case '+': if (selected == 0) counter++; break;
        case '-': if (selected == 0) counter--; break;
        case '\n': case KEY_ENTER:
            if (selected == 0) counter++;
            if (selected == ITEM_COUNT - 1) running = false;
            break;
        case 'q': if (selected != 2) running = false; break;
        case KEY_MOUSE:
            /* A tap on a menu line selects that entry. */
            if (getmouse(&mouse) == OK && wmouse_trafo(menu, &mouse.y, &mouse.x, FALSE)) {
                if (mouse.y >= 1 && mouse.y <= ITEM_COUNT) selected = mouse.y - 1;
            }
            break;
        default: break;
        }
    }
    delwin(menu);
    delwin(panel);
    endwin();
    printf("Counter ended at %d. Bye!\n", counter);
    return 0;
}
