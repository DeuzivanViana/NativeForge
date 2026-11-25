#include <ncurses.h>

#include <array>
#include <clocale>
#include <cstdio>
#include <ctime>
#include <memory>
#include <string>

// A text user interface with ncurses in C++: a menu on the left, a panel on the right and a
// status bar. Use the arrow keys (they are on the bar above the keyboard) or tap a menu entry.
// ncurses draws into windows in memory; doupdate() sends only what changed to the terminal.
// Documentation: man pages at https://invisible-island.net/ncurses/man/

namespace {

enum Pair : short { Title = 1, Selected, Status, Accent };

constexpr std::array<const char*, 5> kItems = {"Counter", "Colors", "Key tester", "About", "Quit"};
constexpr int kItemCount = static_cast<int>(kItems.size());
constexpr int kMenuWidth = 16;

// Starts ncurses and restores the terminal when it goes out of scope, even on early returns.
class Screen {
public:
    Screen() {
        std::setlocale(LC_ALL, ""); // UTF-8 box lines and text
        initscr();
        cbreak();                   // keys arrive at once, without Enter
        noecho();
        curs_set(0);
        keypad(stdscr, TRUE);       // arrow keys as KEY_UP, KEY_DOWN...
        mousemask(BUTTON1_CLICKED | BUTTON1_PRESSED, nullptr);
        timeout(1000);              // getch() returns ERR after a second, so the clock ticks
        if (has_colors()) {
            start_color();
            use_default_colors();
            init_pair(Title, COLOR_BLACK, COLOR_CYAN);
            init_pair(Selected, COLOR_BLACK, COLOR_GREEN);
            init_pair(Status, COLOR_WHITE, COLOR_BLUE);
            init_pair(Accent, COLOR_YELLOW, -1);
        }
    }
    ~Screen() { endwin(); }
    Screen(const Screen&) = delete;
    Screen& operator=(const Screen&) = delete;
};

// An ncurses window that deletes itself.
class Window {
public:
    Window(int height, int width, int y, int x) : win_(newwin(height, width, y, x)) {}
    ~Window() { delwin(win_); }
    Window(const Window&) = delete;
    Window& operator=(const Window&) = delete;
    WINDOW* get() const { return win_; }

private:
    WINDOW* win_;
};

class App {
public:
    void run() {
        while (running_) {
            layoutIfResized();
            draw();
            handle(getch());
        }
    }

    int counter() const { return counter_; }

private:
    void layoutIfResized() {
        if (lines_ == LINES && cols_ == COLS) return; // first frame, or the screen was rotated
        lines_ = LINES;
        cols_ = COLS;
        menu_ = std::make_unique<Window>(LINES - 2, kMenuWidth, 1, 0);
        panel_ = std::make_unique<Window>(LINES - 2, COLS - kMenuWidth, 1, kMenuWidth);
        clear();
    }

    void draw() {
        drawTitle();
        drawStatus();
        wnoutrefresh(stdscr);
        drawMenu(menu_->get());
        drawPanel(panel_->get());
        doupdate();
    }

    void drawTitle() const {
        attron(COLOR_PAIR(Title) | A_BOLD);
        mvhline(0, 0, ' ', COLS);
        mvprintw(0, 1, "@PROJECT_NAME@");
        char clock[16];
        std::time_t now = std::time(nullptr);
        std::strftime(clock, sizeof clock, "%H:%M:%S", std::localtime(&now));
        mvprintw(0, COLS - static_cast<int>(std::string(clock).size()) - 1, "%s", clock);
        attroff(COLOR_PAIR(Title) | A_BOLD);
    }

    void drawStatus() const {
        attron(COLOR_PAIR(Status));
        mvhline(LINES - 1, 0, ' ', COLS);
        mvprintw(LINES - 1, 1, "Up/Down: choose   Enter: act   q: quit");
        attroff(COLOR_PAIR(Status));
    }

    void drawMenu(WINDOW* menu) const {
        werase(menu);
        box(menu, 0, 0);
        mvwprintw(menu, 0, 2, " Menu ");
        for (int i = 0; i < kItemCount; i++) {
            if (i == selected_) wattron(menu, COLOR_PAIR(Selected) | A_BOLD);
            mvwprintw(menu, i + 1, 1, " %-*s", kMenuWidth - 3, kItems[i]);
            if (i == selected_) wattroff(menu, COLOR_PAIR(Selected) | A_BOLD);
        }
        wnoutrefresh(menu);
    }

    void drawPanel(WINDOW* panel) const {
        werase(panel);
        box(panel, 0, 0);
        mvwprintw(panel, 0, 2, " %s ", kItems[selected_]);
        switch (selected_) {
        case 0:
            mvwprintw(panel, 2, 2, "Value: ");
            wattron(panel, COLOR_PAIR(Accent) | A_BOLD);
            wprintw(panel, "%d", counter_);
            wattroff(panel, COLOR_PAIR(Accent) | A_BOLD);
            mvwprintw(panel, 4, 2, "+ or Enter: add one");
            mvwprintw(panel, 5, 2, "- : subtract one");
            break;
        case 1:
            for (short color = 0; color < 8; color++) {
                init_pair(10 + color, COLOR_BLACK, color);
                wattron(panel, COLOR_PAIR(10 + color));
                mvwprintw(panel, 2 + color, 2, "  color %d  ", color);
                wattroff(panel, COLOR_PAIR(10 + color));
            }
            mvwprintw(panel, 11, 2, "This terminal has %d colors", COLORS);
            break;
        case 2:
            mvwprintw(panel, 2, 2, "Press any key.");
            if (lastKey_ >= 0) mvwprintw(panel, 4, 2, "Code %d: %s", lastKey_, keyname(lastKey_));
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

    void handle(int key) {
        if (key == ERR) return;
        if (selected_ == 2 && key != KEY_MOUSE) lastKey_ = key;
        switch (key) {
        case KEY_UP: selected_ = (selected_ + kItemCount - 1) % kItemCount; break;
        case KEY_DOWN: selected_ = (selected_ + 1) % kItemCount; break;
        case '+': if (selected_ == 0) counter_++; break;
        case '-': if (selected_ == 0) counter_--; break;
        case '\n':
        case KEY_ENTER:
            if (selected_ == 0) counter_++;
            if (selected_ == kItemCount - 1) running_ = false;
            break;
        case 'q': if (selected_ != 2) running_ = false; break;
        case KEY_MOUSE: {
            // A tap on a menu line selects that entry.
            MEVENT mouse;
            if (getmouse(&mouse) == OK && wmouse_trafo(menu_->get(), &mouse.y, &mouse.x, FALSE)) {
                if (mouse.y >= 1 && mouse.y <= kItemCount) selected_ = mouse.y - 1;
            }
            break;
        }
        default: break;
        }
    }

    std::unique_ptr<Window> menu_;
    std::unique_ptr<Window> panel_;
    int selected_ = 0;
    int counter_ = 0;
    int lastKey_ = -1;
    int lines_ = 0;
    int cols_ = 0;
    bool running_ = true;
};

} // namespace

int main() {
    int counter = 0;
    {
        Screen screen;
        App app;
        app.run();
        counter = app.counter();
    } // the terminal is restored here
    std::printf("Counter ended at %d. Bye!\n", counter);
    return 0;
}
