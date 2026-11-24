#include <SDL.h>            /* renames main() to SDL_main, which the SDL2 host activity calls */
#include "lvgl.h"
#include "lv_nativeforge.h"

#include <stdio.h>
#include <string.h>

/*
 * A to-do list app made with LVGL: tabs, a text field with the on-screen keyboard, a list
 * of check boxes with delete buttons, a progress arc and a bar chart. Tasks are saved in
 * the app's private folder, so they are still there the next time it starts.
 */

#define MAX_TASKS 64
#define TEXT_SIZE 96

typedef struct {
    char text[TEXT_SIZE];
    bool done;
    lv_obj_t *row;
} task_t;

static task_t tasks[MAX_TASKS];
static int task_count;
static char save_path[512];

static lv_obj_t *list;
static lv_obj_t *input;
static lv_obj_t *keyboard;
static lv_obj_t *progress_arc;
static lv_obj_t *progress_label;
static lv_obj_t *chart;
static lv_chart_series_t *done_series;
static lv_chart_series_t *open_series;

static void save_tasks(void)
{
    FILE *file = fopen(save_path, "w");
    if (file == NULL) return;
    for (int i = 0; i < task_count; i++) fprintf(file, "%d %s\n", tasks[i].done ? 1 : 0, tasks[i].text);
    fclose(file);
}

static void load_tasks(void)
{
    FILE *file = fopen(save_path, "r");
    if (file == NULL) return;
    char line[TEXT_SIZE + 8];
    while (task_count < MAX_TASKS && fgets(line, sizeof line, file)) {
        line[strcspn(line, "\r\n")] = '\0';
        if (strlen(line) < 3) continue;
        tasks[task_count].done = line[0] == '1';
        snprintf(tasks[task_count].text, TEXT_SIZE, "%s", line + 2);
        task_count++;
    }
    fclose(file);
}

/* Updates the Progress tab from the task list. */
static void update_progress(void)
{
    int done = 0;
    for (int i = 0; i < task_count; i++) done += tasks[i].done;
    int percent = task_count ? done * 100 / task_count : 0;
    lv_arc_set_value(progress_arc, percent);
    lv_label_set_text_fmt(progress_label, "%d%%\n%d of %d done", percent, done, task_count);
    lv_chart_set_next_value(chart, done_series, done);
    lv_chart_set_next_value(chart, open_series, task_count - done);
    int32_t max = task_count < 5 ? 5 : task_count;
    lv_chart_set_axis_range(chart, LV_CHART_AXIS_PRIMARY_Y, 0, max);
}

static int index_of_row(lv_obj_t *row)
{
    for (int i = 0; i < task_count; i++) if (tasks[i].row == row) return i;
    return -1;
}

static void on_checked(lv_event_t *e)
{
    lv_obj_t *checkbox = lv_event_get_target_obj(e);
    int i = index_of_row(lv_obj_get_parent(checkbox));
    if (i < 0) return;
    tasks[i].done = lv_obj_has_state(checkbox, LV_STATE_CHECKED);
    save_tasks();
    update_progress();
}

static void on_delete(lv_event_t *e)
{
    lv_obj_t *row = lv_obj_get_parent(lv_event_get_target_obj(e));
    int i = index_of_row(row);
    if (i < 0) return;
    lv_obj_delete_async(row); /* deleting the clicked button's parent right now would be unsafe */
    memmove(&tasks[i], &tasks[i + 1], (size_t)(task_count - i - 1) * sizeof(task_t));
    task_count--;
    save_tasks();
    update_progress();
}

static void add_row(task_t *task)
{
    lv_obj_t *row = lv_obj_create(list);
    lv_obj_set_size(row, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_all(row, 8, 0);
    lv_obj_set_scrollable(row, false);

    lv_obj_t *checkbox = lv_checkbox_create(row);
    lv_checkbox_set_text(checkbox, task->text);
    lv_obj_set_flex_grow(checkbox, 1);
    if (task->done) lv_obj_add_state(checkbox, LV_STATE_CHECKED);
    lv_obj_add_event_cb(checkbox, on_checked, LV_EVENT_VALUE_CHANGED, NULL);

    lv_obj_t *remove = lv_button_create(row);
    lv_obj_set_style_bg_color(remove, lv_palette_main(LV_PALETTE_RED), 0);
    lv_label_set_text(lv_label_create(remove), LV_SYMBOL_TRASH);
    lv_obj_add_event_cb(remove, on_delete, LV_EVENT_CLICKED, NULL);
    task->row = row;
}

static void add_task(void)
{
    const char *text = lv_textarea_get_text(input);
    if (text[0] == '\0' || task_count == MAX_TASKS) return;
    task_t *task = &tasks[task_count++];
    snprintf(task->text, TEXT_SIZE, "%s", text);
    task->done = false;
    add_row(task);
    lv_textarea_set_text(input, "");
    save_tasks();
    update_progress();
    lv_obj_scroll_to_view(task->row, LV_ANIM_ON);
}

static void on_add(lv_event_t *e)
{
    LV_UNUSED(e);
    add_task();
}

static void on_input(lv_event_t *e)
{
    lv_event_code_t code = lv_event_get_code(e);
    if (code == LV_EVENT_FOCUSED || code == LV_EVENT_CLICKED) {
        lv_keyboard_set_textarea(keyboard, input);
        lv_obj_set_hidden(keyboard, false);
    } else if (code == LV_EVENT_READY) {
        add_task();
    }
    if (code == LV_EVENT_READY || code == LV_EVENT_CANCEL || code == LV_EVENT_DEFOCUSED) {
        lv_obj_set_hidden(keyboard, true);
        lv_obj_remove_state(input, LV_STATE_FOCUSED);
    }
}

static void build_tasks_tab(lv_obj_t *tab)
{
    lv_obj_set_flex_flow(tab, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(tab, 10, 0);

    lv_obj_t *bar = lv_obj_create(tab);
    lv_obj_remove_style_all(bar);
    lv_obj_set_size(bar, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(bar, LV_FLEX_FLOW_ROW);
    lv_obj_set_style_pad_column(bar, 8, 0);
    input = lv_textarea_create(bar);
    lv_textarea_set_one_line(input, true);
    lv_textarea_set_max_length(input, TEXT_SIZE - 1);
    lv_textarea_set_placeholder_text(input, "New task");
    lv_obj_set_flex_grow(input, 1);
    lv_obj_add_event_cb(input, on_input, LV_EVENT_ALL, NULL);
    lv_obj_t *add = lv_button_create(bar);
    lv_label_set_text(lv_label_create(add), LV_SYMBOL_PLUS);
    lv_obj_add_event_cb(add, on_add, LV_EVENT_CLICKED, NULL);

    list = lv_obj_create(tab);
    lv_obj_remove_style_all(list);
    lv_obj_set_width(list, lv_pct(100));
    lv_obj_set_flex_grow(list, 1);
    lv_obj_set_flex_flow(list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(list, 6, 0);
    lv_obj_set_scrollable(list, true);
    for (int i = 0; i < task_count; i++) add_row(&tasks[i]);
}

static void build_progress_tab(lv_obj_t *tab)
{
    lv_obj_set_flex_flow(tab, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(tab, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);

    progress_arc = lv_arc_create(tab);
    lv_obj_set_size(progress_arc, 200, 200);
    lv_arc_set_rotation(progress_arc, 135);
    lv_arc_set_bg_angles(progress_arc, 0, 270);
    lv_obj_set_clickable(progress_arc, false); /* display only */
    lv_obj_set_style_arc_width(progress_arc, 18, LV_PART_MAIN);
    lv_obj_set_style_arc_width(progress_arc, 18, LV_PART_INDICATOR);
    progress_label = lv_label_create(progress_arc);
    lv_obj_set_style_text_align(progress_label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_center(progress_label);

    lv_obj_t *caption = lv_label_create(tab);
    lv_label_set_text(caption, "History (done / open)");
    chart = lv_chart_create(tab);
    lv_obj_set_size(chart, lv_pct(95), 180);
    lv_chart_set_type(chart, LV_CHART_TYPE_BAR);
    lv_chart_set_point_count(chart, 12);
    done_series = lv_chart_add_series(chart, lv_palette_main(LV_PALETTE_GREEN), LV_CHART_AXIS_PRIMARY_Y);
    open_series = lv_chart_add_series(chart, lv_palette_main(LV_PALETTE_ORANGE), LV_CHART_AXIS_PRIMARY_Y);
}

int main(int argc, char *argv[])
{
    LV_UNUSED(argc);
    LV_UNUSED(argv);
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_AUTO) == NULL) return 1;

    char *dir = lv_nativeforge_data_dir("@PROJECT_NAME@");
    snprintf(save_path, sizeof save_path, "%stasks.txt", dir ? dir : "");
    SDL_free(dir);
    load_tasks();
    if (task_count == 0) {
        const char *examples[] = {"Try LVGL in NativeForge", "Add a task with the + button", "Tap a task to finish it"};
        for (int i = 0; i < 3; i++) {
            snprintf(tasks[i].text, TEXT_SIZE, "%s", examples[i]);
            tasks[i].done = i == 0;
        }
        task_count = 3;
    }

    lv_obj_t *tabs = lv_tabview_create(lv_screen_active());
    lv_tabview_set_tab_bar_position(tabs, LV_DIR_BOTTOM);
    build_tasks_tab(lv_tabview_add_tab(tabs, LV_SYMBOL_LIST " Tasks"));
    build_progress_tab(lv_tabview_add_tab(tabs, LV_SYMBOL_EYE_OPEN " Progress"));
    update_progress();

    keyboard = lv_keyboard_create(lv_layer_top());
    lv_obj_set_hidden(keyboard, true);
    lv_obj_add_event_cb(keyboard, on_input, LV_EVENT_READY, NULL);
    lv_obj_add_event_cb(keyboard, on_input, LV_EVENT_CANCEL, NULL);

    lv_nativeforge_run();
    return 0;
}
