#include <SDL.h>            // renames main() to SDL_main, which the SDL2 host activity calls
#include "lvgl.h"
#include "lv_nativeforge.h"

#include <algorithm>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

/*
 * A to-do list app made with LVGL in C++: tabs, a text field with the on-screen keyboard, a
 * list of check boxes with delete buttons, a progress arc and a bar chart. Tasks are saved in
 * the app's private folder, so they are still there the next time it starts.
 *
 * LVGL is a C library; its headers declare everything extern "C", so C++ code calls it
 * directly. Callbacks are plain functions (or lambdas without captures) that get the
 * TaskApp back from the event's user data.
 */

namespace {

constexpr std::size_t MAX_TEXT = 95;
constexpr std::size_t MAX_TASKS = 200;

struct Task {
    std::string text;
    bool done = false;
    lv_obj_t *row = nullptr; // the list row that shows this task
};

class TaskApp {
public:
    explicit TaskApp(std::string savePath) : savePath_(std::move(savePath)) { load(); }

    void build(lv_obj_t *screen)
    {
        lv_obj_t *tabs = lv_tabview_create(screen);
        lv_tabview_set_tab_bar_position(tabs, LV_DIR_BOTTOM);
        buildTasksTab(lv_tabview_add_tab(tabs, LV_SYMBOL_LIST " Tasks"));
        buildProgressTab(lv_tabview_add_tab(tabs, LV_SYMBOL_EYE_OPEN " Progress"));
        updateProgress();

        keyboard_ = lv_keyboard_create(lv_layer_top());
        lv_obj_set_hidden(keyboard_, true);
        lv_obj_add_event_cb(keyboard_, onInput, LV_EVENT_READY, this);
        lv_obj_add_event_cb(keyboard_, onInput, LV_EVENT_CANCEL, this);
    }

private:
    void load()
    {
        std::ifstream file(savePath_);
        std::string line;
        while (tasks_.size() < MAX_TASKS && std::getline(file, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.size() < 3) continue;
            tasks_.push_back({line.substr(2, MAX_TEXT), line[0] == '1'});
        }
        if (tasks_.empty()) {
            tasks_ = {{"Try LVGL in NativeForge", true}, {"Add a task with the + button"}, {"Tap a task to finish it"}};
        }
    }

    void save() const
    {
        std::ofstream file(savePath_);
        for (const Task &task : tasks_) file << (task.done ? '1' : '0') << ' ' << task.text << '\n';
    }

    Task *taskOf(lv_obj_t *row)
    {
        auto it = std::find_if(tasks_.begin(), tasks_.end(), [row](const Task &t) { return t.row == row; });
        return it == tasks_.end() ? nullptr : &*it;
    }

    static TaskApp &from(lv_event_t *e) { return *static_cast<TaskApp *>(lv_event_get_user_data(e)); }

    /* Updates the Progress tab from the task list. */
    void updateProgress()
    {
        const auto total = static_cast<int32_t>(tasks_.size());
        const auto done = static_cast<int32_t>(std::count_if(tasks_.begin(), tasks_.end(), [](const Task &t) { return t.done; }));
        const int32_t percent = total ? done * 100 / total : 0;
        lv_arc_set_value(progressArc_, percent);
        lv_label_set_text_fmt(progressLabel_, "%d%%\n%d of %d done", static_cast<int>(percent), static_cast<int>(done),
                              static_cast<int>(total));
        lv_chart_set_next_value(chart_, doneSeries_, done);
        lv_chart_set_next_value(chart_, openSeries_, total - done);
        lv_chart_set_axis_range(chart_, LV_CHART_AXIS_PRIMARY_Y, 0, std::max<int32_t>(5, total));
    }

    void addRow(Task &task)
    {
        lv_obj_t *row = lv_obj_create(list_);
        lv_obj_set_size(row, lv_pct(100), LV_SIZE_CONTENT);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_pad_all(row, LV_DPX(8), 0);
        lv_obj_set_scrollable(row, false);

        lv_obj_t *checkbox = lv_checkbox_create(row);
        lv_checkbox_set_text(checkbox, "");
        if (task.done) lv_obj_add_state(checkbox, LV_STATE_CHECKED);
        lv_obj_add_event_cb(checkbox, [](lv_event_t *e) {
            TaskApp &app = from(e);
            lv_obj_t *box = lv_event_get_target_obj(e);
            if (Task *t = app.taskOf(lv_obj_get_parent(box))) {
                t->done = lv_obj_has_state(box, LV_STATE_CHECKED);
                app.save();
                app.updateProgress();
            }
        }, LV_EVENT_VALUE_CHANGED, this);

        // The text is a label of its own so that long tasks wrap onto more lines on narrow
        // screens (a checkbox keeps its text on one line). A tap on it ticks the box too.
        lv_obj_t *text = lv_label_create(row);
        lv_label_set_text(text, task.text.c_str());
        lv_label_set_long_mode(text, LV_LABEL_LONG_MODE_WRAP);
        lv_obj_set_flex_grow(text, 1);
        lv_obj_set_clickable(text, true);
        lv_obj_add_event_cb(text, [](lv_event_t *e) {
            auto *box = static_cast<lv_obj_t *>(lv_event_get_user_data(e));
            if (lv_obj_has_state(box, LV_STATE_CHECKED)) lv_obj_remove_state(box, LV_STATE_CHECKED);
            else lv_obj_add_state(box, LV_STATE_CHECKED);
            lv_obj_send_event(box, LV_EVENT_VALUE_CHANGED, nullptr);
        }, LV_EVENT_CLICKED, checkbox);

        lv_obj_t *remove = lv_button_create(row);
        lv_obj_set_style_bg_color(remove, lv_palette_main(LV_PALETTE_RED), 0);
        lv_label_set_text(lv_label_create(remove), LV_SYMBOL_TRASH);
        lv_obj_add_event_cb(remove, [](lv_event_t *e) {
            TaskApp &app = from(e);
            lv_obj_t *row = lv_obj_get_parent(lv_event_get_target_obj(e));
            Task *t = app.taskOf(row);
            if (t == nullptr) return;
            lv_obj_delete_async(row); // deleting the clicked button's parent right now would be unsafe
            app.tasks_.erase(app.tasks_.begin() + (t - app.tasks_.data()));
            app.save();
            app.updateProgress();
        }, LV_EVENT_CLICKED, this);
        task.row = row;
    }

    void addTask()
    {
        std::string text = lv_textarea_get_text(input_);
        if (text.empty() || tasks_.size() >= MAX_TASKS) return;
        // Adding to the vector may move the other tasks; that is fine because rows are
        // found by comparing pointers stored in the tasks, not pointers to the tasks.
        tasks_.push_back({text.substr(0, MAX_TEXT)});
        addRow(tasks_.back());
        lv_textarea_set_text(input_, "");
        save();
        updateProgress();
        lv_obj_scroll_to_view(tasks_.back().row, LV_ANIM_ON);
    }

    static void onInput(lv_event_t *e)
    {
        TaskApp &app = from(e);
        const lv_event_code_t code = lv_event_get_code(e);
        if (code == LV_EVENT_FOCUSED || code == LV_EVENT_CLICKED) {
            lv_keyboard_set_textarea(app.keyboard_, app.input_);
            lv_obj_set_hidden(app.keyboard_, false);
        } else if (code == LV_EVENT_READY) {
            app.addTask();
        }
        if (code == LV_EVENT_READY || code == LV_EVENT_CANCEL || code == LV_EVENT_DEFOCUSED) {
            lv_obj_set_hidden(app.keyboard_, true);
            lv_obj_remove_state(app.input_, LV_STATE_FOCUSED);
        }
    }

    void buildTasksTab(lv_obj_t *tab)
    {
        lv_obj_set_flex_flow(tab, LV_FLEX_FLOW_COLUMN);
        lv_obj_set_style_pad_row(tab, LV_DPX(10), 0);

        lv_obj_t *bar = lv_obj_create(tab);
        lv_obj_remove_style_all(bar);
        lv_obj_set_size(bar, lv_pct(100), LV_SIZE_CONTENT);
        lv_obj_set_flex_flow(bar, LV_FLEX_FLOW_ROW);
        lv_obj_set_style_pad_column(bar, LV_DPX(8), 0);
        input_ = lv_textarea_create(bar);
        lv_textarea_set_one_line(input_, true);
        lv_textarea_set_max_length(input_, MAX_TEXT);
        lv_textarea_set_placeholder_text(input_, "New task");
        lv_obj_set_flex_grow(input_, 1);
        lv_obj_add_event_cb(input_, onInput, LV_EVENT_ALL, this);
        lv_obj_t *add = lv_button_create(bar);
        lv_label_set_text(lv_label_create(add), LV_SYMBOL_PLUS);
        lv_obj_add_event_cb(add, [](lv_event_t *e) { from(e).addTask(); }, LV_EVENT_CLICKED, this);

        list_ = lv_obj_create(tab);
        lv_obj_remove_style_all(list_);
        lv_obj_set_width(list_, lv_pct(100));
        lv_obj_set_flex_grow(list_, 1);
        lv_obj_set_flex_flow(list_, LV_FLEX_FLOW_COLUMN);
        lv_obj_set_style_pad_row(list_, LV_DPX(6), 0);
        lv_obj_set_scrollable(list_, true);
        for (Task &task : tasks_) addRow(task);
    }

    void buildProgressTab(lv_obj_t *tab)
    {
        lv_obj_set_flex_flow(tab, LV_FLEX_FLOW_COLUMN);
        lv_obj_set_flex_align(tab, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);

        progressArc_ = lv_arc_create(tab);
        lv_obj_set_size(progressArc_, LV_DPX(200), LV_DPX(200));
        lv_arc_set_rotation(progressArc_, 135);
        lv_arc_set_bg_angles(progressArc_, 0, 270);
        lv_obj_set_clickable(progressArc_, false); // display only
        lv_obj_set_style_arc_width(progressArc_, LV_DPX(18), LV_PART_MAIN);
        lv_obj_set_style_arc_width(progressArc_, LV_DPX(18), LV_PART_INDICATOR);
        progressLabel_ = lv_label_create(progressArc_);
        lv_obj_set_style_text_align(progressLabel_, LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_center(progressLabel_);

        lv_label_set_text(lv_label_create(tab), "History (done / open)");
        chart_ = lv_chart_create(tab);
        lv_obj_set_size(chart_, lv_pct(95), LV_DPX(180));
        lv_chart_set_type(chart_, LV_CHART_TYPE_BAR);
        lv_chart_set_point_count(chart_, 12);
        doneSeries_ = lv_chart_add_series(chart_, lv_palette_main(LV_PALETTE_GREEN), LV_CHART_AXIS_PRIMARY_Y);
        openSeries_ = lv_chart_add_series(chart_, lv_palette_main(LV_PALETTE_ORANGE), LV_CHART_AXIS_PRIMARY_Y);
    }

    std::string savePath_;
    std::vector<Task> tasks_;
    lv_obj_t *list_ = nullptr;
    lv_obj_t *input_ = nullptr;
    lv_obj_t *keyboard_ = nullptr;
    lv_obj_t *progressArc_ = nullptr;
    lv_obj_t *progressLabel_ = nullptr;
    lv_obj_t *chart_ = nullptr;
    lv_chart_series_t *doneSeries_ = nullptr;
    lv_chart_series_t *openSeries_ = nullptr;
};

std::string dataFile(const char *name)
{
    char *dir = lv_nativeforge_data_dir("@PROJECT_NAME@");
    std::string path = std::string(dir ? dir : "") + name;
    SDL_free(dir);
    return path;
}

} // namespace

int main(int, char *[])
{
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_PIXELS) == nullptr) return 1;
    static TaskApp app(dataFile("tasks.txt")); // lives until the program ends, like the screen
    app.build(lv_screen_active());
    lv_nativeforge_run();
    return 0;
}
