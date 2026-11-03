// GFrame/libCommon 9.5.0.600013 arm64 adapter. Offsets are tied to the audited baseline.
#define NS_ENTRY 0x7e100001
#define NS_PANEL 0x7e100002
#define NS_SCROLL 0x7e100003
#define NS_EDIT 0x7e100004

typedef struct {
    int visible, selected, mode, reset, count, input_type;
    uint16_t *title, *draft, **titles, **values;
    jint *kinds, *actions, *help;
} SettingsModel;

static JavaVM *ns_vm;
static jclass ns_class;
static jmethodID ns_action;
static char *ns_base;
static void *ns_context;
static int ns_night = -1, ns_width, ns_height, ns_previous_tab = -1;
static float ns_unit;
static int ns_list_top[5], ns_last_mode = -1, ns_last_section;
#define NS_FN(type, offset) ((type)(ns_base + (offset)))

static void ns_callback(int action, const uint16_t *input) {
    JNIEnv *env;
    int attached = (*ns_vm)->GetEnv(ns_vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached) (*ns_vm)->AttachCurrentThread(ns_vm, &env, 0);
    jstring text = 0;
    if (input) {
        int count = 0; while (input[count]) count++;
        text = (*env)->NewString(env, input, count);
    }
    (*env)->CallStaticVoidMethod(env, ns_class, ns_action, action, text);
    if (text) (*env)->DeleteLocalRef(env, text);
    if (attached) (*ns_vm)->DetachCurrentThread(ns_vm);
}

static void *ns_find(void *view, int id) {
    if (*(int *)((char *)view + 0x578) == id) return view;
    for (void *child = *(void **)((char *)view + 0x48); child;
            child = *(void **)((char *)child + 0x10)) {
        void *result = ns_find(child, id);
        if (result) return result;
    }
    return 0;
}

static void ns_clicked(void *owner, void *view) {
    int action = (int)(intptr_t)owner;
    if (action >= 20 && action <= 22) {
        void *common = dlopen("libCommon.so", RTLD_NOW | RTLD_NOLOAD);
        void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
        void *(*instance)(void) = dlsym(common, "_ZN21KeyboardDialogManager8InstanceEv");
        void *(*activity)(void *) = dlsym(frame, "_ZN9GNS_FRAME6CGView15getHostActivityEv");
        void (*show)(void *, void *) = dlsym(common, "_ZN21KeyboardDialogManager18ShowKeyboardDialogERK13KeyboardParam");
        unsigned char params[80] = {0};
        *(void **)(params + 0x0) = *(void **)((char *)view + 0x6c0);
        *(void **)(params + 0x8) = view;
        *(void **)(params + 0x18) = activity(view);
        *(unsigned int *)(params + 0x40) = 0x01000001;
        *(int *)(params + 0x44) = action == 22 ? 2 : 0;
        *(int *)(params + 0x48) = action == 20 ? 0 : 4;
        show(instance(), params);
        dlclose(frame); dlclose(common);
        return;
    }
    void *root = view;
    while (*(void **)((char *)root + 8)) root = *(void **)((char *)root + 8);
    void *editor = ns_find(root, NS_EDIT);
    ns_callback((int)(intptr_t)owner, editor ? *(uint16_t **)((char *)editor + 0xa48) : 0);
}

static void ns_click(void *view, int action) {
    if (!action) return;
    void *common = dlopen("libCommon.so", RTLD_NOW | RTLD_NOLOAD);
    Dl_info info;
    dladdr(dlsym(common, "_ZN21CGCustomBtnSwitchViewC1EPN9GNS_FRAME9CGContextE"), &info);
    unsigned char callable[64] = {0};
    // Reuse the engine's member-callback constructor, copy and reference-counting ABI.
    // Both stored binders have the standard arm64 non-virtual member-function pair.
    void (*construct)(void *, void *) = (void *)((char *)info.dli_fbase + 0x10afe68);
    construct(callable, (void *)(intptr_t)action);
    for (int i = 0; i < 2; i++) {
        void *binder = *(void **)(callable + (i == 0 ? 0x18 : 0x8));
        *(void **)((char *)binder + 0x10) = ns_clicked;
        *(intptr_t *)((char *)binder + 0x18) = 0;
    }
    NS_FN(void (*)(void *, int), 0x629c84)(view, 1);
    NS_FN(void (*)(void *, void *, void *, const char *, const char *, unsigned long), 0x6387f0)(
        view, callable, view, "NativeSettings", "native_settings", 0);
    void (*destroy)(void *) = (*(void ***)callable)[0];
    destroy(callable);
    dlclose(common);
}

static void *ns_node(void *view) {
    if (*(int *)((char *)view + 0x488) == 0x1f)
        return NS_FN(void *(*)(void *), 0x48cb48)(view);
    void *params = NS_FN(void *(*)(void *), 0x48c14c)(view);
    return params ? NS_FN(void *(*)(void *), 0x48cab4)(params) : 0;
}
static void ns_size(void *view, float width, float height) {
    void *node = ns_node(view);
    void *params = *(void **)((char *)view + 0x648);
    if (width >= 0) *(int *)((char *)params + 0x18) = (int)width;
    if (height >= 0) *(int *)((char *)params + 0x1c) = (int)height;
    if (node) {
        if (width >= 0) NS_FN(void (*)(void *, float), 0x6bee0c)(node, width);
        if (height >= 0) NS_FN(void (*)(void *, float), 0x6bf0f8)(node, height);
        NS_FN(void (*)(void *, float), 0x6be024)(node, 0);
    }
}
static void ns_absolute(void *view, float left, float top, float width, float height) {
    void *node = ns_node(view);
    if (node) {
        NS_FN(void (*)(void *, int), 0x6bdce8)(node, 1);
        NS_FN(void (*)(void *, int, float), 0x6be3d8)(node, 0, left);
        NS_FN(void (*)(void *, int, float), 0x6be3d8)(node, 1, top);
    }
    ns_size(view, width, height);
    void *params = *(void **)((char *)view + 0x648);
    *(int *)((char *)params + 0x30) = (int)left;
    *(int *)((char *)params + 0x34) = (int)top;
}
static void ns_padding(void *view, float pad) {
    void *node = ns_node(view);
    if (*(int *)((char *)view + 0x488) == 0x1f) {
        NS_FN(void (*)(void *, int, float), 0x6be960)(node, 8, pad);
    } else {
        NS_FN(void (*)(void *, int, float), 0x6be960)(node, 0, pad);
        NS_FN(void (*)(void *, int, float), 0x6be960)(node, 2, pad);
        void *params = *(void **)((char *)view + 0x648);
        *(int *)((char *)params + 0x40) = (int)pad;
        *(int *)((char *)params + 0x48) = (int)pad;
    }
}
static void ns_background(void *view, unsigned int color) {
    unsigned char storage[32] = {0};
    void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
    void (*construct)(void *, unsigned int) = dlsym(frame, "_ZN7CGColorC1Ej");
    void (*destroy)(void *) = dlsym(frame, "_ZN7CGColorD1Ev");
    construct(storage, color);
    void *drawable = NS_FN(void *(*)(const void *), 0x3387c4)(storage);
    NS_FN(void (*)(void *, const void *), 0x63112c)(view, &drawable);
    destroy(storage);
    dlclose(frame);
}
static void *ns_create(const uint16_t *tag, void *parent, int id) {
    unsigned char name[64] = {0};
    void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
    void (*construct)(void *, const uint16_t *) = dlsym(frame, "_ZN8CGStringC1EPKt");
    void (*destroy)(void *) = dlsym(frame, "_ZN8CGStringD1Ev");
    construct(name, tag);
    void *view = NS_FN(void *(*)(void *, void *), 0x620a50)(name, ns_context);
    destroy(name);
    dlclose(frame);
    if (!view) return 0;
    *(int *)((char *)view + 0x578) = id;
    void *params = malloc(0x68);
    NS_FN(void (*)(void *, void *), 0x48bb40)(params, view);
    NS_FN(int (*)(void *, void *), 0x622838)(view, params);
    if (parent) NS_FN(void (*)(void *, void *), 0x5c2948)(parent, view);
    return view;
}
static void ns_text(void *view, const uint16_t *text, int size, unsigned int color, int gravity) {
    int length = 0; while (text[length]) length++;
    uint16_t *format = malloc((length * 2 + 1) * 2);
    int j = 0;
    for (int i = 0; i < length; i++) {
        format[j++] = text[i];
        if (text[i] == '%') format[j++] = '%';
    }
    format[j] = 0;
    NS_FN(void (*)(void *, const uint16_t *, ...), 0x3a7000)(view, format);
    free(format);
    NS_FN(void (*)(void *, int), 0x3a7c48)(view, size);
    unsigned char native_color[32] = {0}, colors[80] = {0};
    void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
    void (*color_construct)(void *, unsigned int) = dlsym(frame, "_ZN7CGColorC1Ej");
    void (*color_destroy)(void *) = dlsym(frame, "_ZN7CGColorD1Ev");
    color_construct(native_color, color);
    NS_FN(void (*)(void *, void *), 0x3d6c34)(colors, native_color);
    NS_FN(void (*)(void *, void *), 0x3a72b4)(view, colors);
    NS_FN(void (*)(void *), 0x3d6c74)(colors);
    color_destroy(native_color);
    dlclose(frame);
    NS_FN(void (*)(void *, int), 0x3a7f94)(view, gravity);
}
static void *ns_label(void *parent, const uint16_t *text, int size, unsigned int color,
        float width, float height, int action) {
    void *view = ns_create(u"TextView", parent, 0);
    if (!view) return 0;
    ns_text(view, text, size * ns_unit, color, 0x11);
    ns_size(view, width, height);
    ns_click(view, action);
    return view;
}
static void *ns_flex(void *parent, int id, int row) {
    void *view = ns_create(u"FlexboxLayout", parent, id);
    if (!view) return 0;
    void *node = NS_FN(void *(*)(void *), 0x48cb48)(view);
    NS_FN(void (*)(void *, int), 0x6bda54)(node, row ? 2 : 0);
    return view;
}

static void ns_switch(void *parent, int checked, int action, float width, float height) {
    void *holder = ns_flex(parent, 0, 1);
    ns_size(holder, width, height);
    NS_FN(void (*)(void *, int), 0x6bdbe0)(ns_node(holder), 2);
    NS_FN(void (*)(void *, int), 0x6bdad8)(ns_node(holder), 1);
    void *common = dlopen("libCommon.so", RTLD_NOW | RTLD_NOLOAD);
    void (*construct)(void *, void *) = dlsym(common, "_ZN21CGCustomBtnSwitchViewC1EPN9GNS_FRAME9CGContextE");
    void (*content)(void *, void *) = dlsym(common, "_ZN21CGCustomBtnSwitchView11loadContentEPN9GNS_FRAME12CGAttributesE");
    void (*attributes)(void *, void *) = dlsym(common, "_ZN21CGCustomBtnSwitchView16onLoadAttributesEPN9GNS_FRAME12CGAttributesE");
    void (*set_checked)(void *, int) = dlsym(common, "_ZN21CGCustomBtnSwitchView10setCheckedEb");
    void *control = calloc(1, 0x9a8);
    construct(control, ns_context);
    NS_FN(void (*)(void *, void *), 0x5c2948)(holder, control);
    content(control, 0);
    unsigned char attrs[128] = {0};
    NS_FN(void (*)(void *), 0x506fd8)(attrs);
    attributes(control, attrs);
    NS_FN(void (*)(void *), 0x507000)(attrs);
    ns_size(control, 96 * ns_unit, 38 * ns_unit);
    set_checked(control, checked);
    ns_click(ns_find(control, 0x2070040a), action);
    dlclose(common);
}

static uint16_t *ns_copy_string(JNIEnv *env, jstring text) {
    jsize count = (*env)->GetStringLength(env, text);
    uint16_t *copy = calloc(count + 1, 2);
    (*env)->GetStringRegion(env, text, 0, count, copy);
    return copy;
}
static void ns_free_model(SettingsModel *model) {
    if (!model) return;
    free(model->title); free(model->draft);
    for (int i = 0; i < model->count; i++) { free(model->titles[i]); free(model->values[i]); }
    free(model->titles); free(model->values); free(model->kinds); free(model->actions); free(model->help);
    free(model);
}


static void ns_render(void *fragment, void *frame, SettingsModel *model) {
    const char *name = (const char *)((void **)(*(void ***)fragment)[-1])[1];
    if (!strstr(name, "SettingMainFragment")) return;
    Dl_info info;
    dladdr(dlsym(frame, "_ZN9GNS_FRAME13CGViewHandlerC2Ev"), &info);
    ns_base = info.dli_fbase;
    void *(*context)(void *) = dlsym(frame, "_ZN9GNS_FRAME10CGFragment10getContextEv");
    int (*is_night)(void *) = dlsym(frame, "_ZNK9GNS_FRAME9CGContext7isNightEv");
    ns_context = context(fragment);
    void *root = *(void **)((char *)fragment + 0x3b0);
    int width = *(int *)((char *)root + 0xb8), height = *(int *)((char *)root + 0xbc);
    if (width <= 0 || height <= 0) return;
    int night = is_night(ns_context);
    int changed = night != ns_night || width != ns_width || height != ns_height;
    ns_night = night; ns_width = width; ns_height = height;
    void *host = ns_find(root, 0x207007d1);
    void *last_tab = ns_find(root, 0x20700337);
    void *navigation = last_tab ? *(void **)((char *)last_tab + 8) : 0;
    if (!navigation || !host || !last_tab) return;
    // The bundled settings tabs are 96 design units high; use their actual native scale.
    ns_unit = *(int *)((char *)last_tab + 0xbc) / 96.0f;
    float u = ns_unit;
    unsigned int bg = night ? 0xff000000 : 0xffeff0f8;
    unsigned int surface = night ? 0xff19191b : 0xffffffff;
    unsigned int fg = night ? 0xffe0e0e0 : 0xff202020;
    unsigned int secondary = night ? 0xff999999 : 0xff666666;
    unsigned int selected = night ? 0xff263e60 : 0xffe7f0ff;
    unsigned int blue = night ? 0xff70a9ff : 0xff287cff;
    int entry_top = *(int *)((char *)last_tab + 0xcc) - *(int *)((char *)root + 0xcc)
            + *(int *)((char *)last_tab + 0xbc);
    int header_entry = entry_top + *(int *)((char *)last_tab + 0xbc) > height;
    void *entry = ns_find(root, NS_ENTRY);
    if (!entry) {
        entry = ns_create(u"TextView", root, NS_ENTRY);
        if (!entry) return;
        ns_click(entry, 8);
        ns_previous_tab = -1;
        ns_last_mode = -1;
        memset(ns_list_top, 0, sizeof(ns_list_top));
        const int tabs[] = {0x20700335, 0x20700336, 0x20700337, 0x20700338};
        for (int i = 0; i < 4; i++) {
            void *tab = ns_find(navigation, tabs[i]);
            if (tab) ns_click(tab, 16);
        }
        changed = 1;
    }
    if (changed) {
        int tab_width = *(int *)((char *)last_tab + 0xb8);
        int tab_height = *(int *)((char *)last_tab + 0xbc);
        ns_text(entry, header_entry ? u"扩展" : u"⋯\n扩展", 18 * u, secondary, 0x11);
        int entry_left = *(int *)((char *)last_tab + 0xc8) - *(int *)((char *)root + 0xc8);
        if (header_entry) {
            entry_left = width - tab_width;
            entry_top = 0;
            tab_height = *(int *)((char *)ns_find(root, 0x20700061) + 0xbc);
        }
        ns_size(entry, tab_width, tab_height);
        // Position inside the visible root; landscape has no space for a fifth sidebar tab.
        void *params = *(void **)((char *)entry + 0x648);
        *(int *)((char *)params + 0x68) = 0x10700000;
        *(int *)((char *)params + 0x78) = 0x10700000;
        *(int *)((char *)params + 0x30) = entry_left;
        *(int *)((char *)params + 0x34) = entry_top;
        ns_background(entry, bg);
        // Entry is a child of root; invalidate its cached constraint solution.
        NS_FN(void (*)(void *), 0x5b1a28)(root);
        NS_FN(void (*)(void *, int), 0x625ce0)(root, 1);
    }
    void *old = ns_find(root, NS_PANEL);
    if (!model) {
        if (changed && old) {
            void *edit = ns_find(old, NS_EDIT);
            ns_callback(7, edit ? *(uint16_t **)((char *)edit + 0xa48) : 0);
        }
        return;
    }
    const int tab_views[4][4] = {
        {0x20700336, 0x2070020c, 0x207004f4, 0x207006ed},
        {0x20700338, 0x207002b8, 0x2070050c, 0x2070071c},
        {0x20700335, 0x20700200, 0x20700554, 0x207006e4},
        {0x20700337, 0x2070021f, 0x207004fe, 0x20700709}
    };
    int active_tab = -1;
    for (int i = 0; i < 4; i++) {
        void *tab = ns_find(navigation, tab_views[i][0]);
        if (*((unsigned char *)tab + 0x69e)) active_tab = i;
    }
    if (model->visible && !old && active_tab >= 0) {
        ns_previous_tab = active_tab;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++)
            NS_FN(void (*)(void *, int), 0x629e6c)(ns_find(navigation, tab_views[i][j]), 0);
    }
    if (!model->visible && ns_previous_tab >= 0) {
        if (active_tab < 0) for (int j = 0; j < 4; j++)
            NS_FN(void (*)(void *, int), 0x629e6c)(ns_find(navigation, tab_views[ns_previous_tab][j]), 1);
        ns_previous_tab = -1;
    }
    ns_text(entry, header_entry ? u"扩展" : u"⋯\n扩展", 18 * u, model->visible ? blue : secondary, 0x11);
    ns_background(entry, model->visible ? surface : bg);
    width = *(int *)((char *)host + 0xb8);
    height = *(int *)((char *)host + 0xbc);
    int top = 0;
    if (old) {
        void *scroll = ns_find(old, NS_SCROLL);
        int previous_top = scroll ? NS_FN(int (*)(void *), 0x675d08)(scroll) : 0;
        if (ns_last_mode == 0) ns_list_top[ns_last_section] = previous_top;
        if (!model->reset) top = model->mode == 0 ? ns_list_top[model->selected]
                : ns_last_mode == model->mode ? previous_top : 0;
        void *edit = ns_find(old, NS_EDIT);
        if (edit) {
            void *common = dlopen("libCommon.so", RTLD_NOW | RTLD_NOLOAD);
            void *(*instance)(void) = dlsym(common, "_ZN21KeyboardDialogManager8InstanceEv");
            void (*close)(void *, int) = dlsym(common, "_ZN21KeyboardDialogManager19CloseKeyboardDialogEb");
            close(instance(), 0);
            dlclose(common);
            NS_FN(void (*)(void *, int), 0x4fa0e8)(edit, 0);
        }
        NS_FN(int (*)(void *, int), 0x627cb8)(old, 0);
    }
    ns_last_mode = model->mode;
    ns_last_section = model->selected;
    if (model->reset && model->mode == 0) ns_list_top[model->selected] = 0;
    if (!model->visible) return;
    void *panel = ns_flex(host, NS_PANEL, 0);
    if (!panel) return;
    ns_absolute(panel, 0, 0, width, height);
    ns_background(panel, bg);
    NS_FN(void (*)(void *, int), 0x629c84)(panel, 1);
    void *header = ns_flex(panel, 0, 1);
    ns_size(header, width, 64 * u); ns_background(header, bg);
    ns_label(header, u"‹", 32, fg, 64 * u, 64 * u, 1);
    void *title = ns_label(header, model->title, 23, fg, width - 64 * u, 64 * u, 0);
    NS_FN(void (*)(void *, int), 0x3a7f94)(title, 0x13);
    void *body = ns_flex(panel, 0, 1);
    ns_size(body, width, height - 64 * u);
    int wide = width >= 720 * u;
    NS_FN(void (*)(void *, int), 0x6bda54)(ns_node(body), wide ? 2 : 0);
    float sidebar = model->mode == 0 && wide ? 176 * u : 0;
    float tab_height = model->mode == 0 && !wide ? 52 * u : 0;
    if (model->mode == 0) {
        void *tabs = ns_flex(body, 0, !wide);
        ns_size(tabs, wide ? sidebar : width, wide ? height - 64 * u : tab_height);
        if (wide) ns_padding(tabs, 12 * u);
        const uint16_t *groups[] = {u"声音播报", u"红绿灯", u"天气", u"显示", u"悬浮窗"};
        for (int i = 0; i < 5; i++) {
            void *tab = ns_label(tabs, groups[i], wide ? 19 : 16, i == model->selected ? blue : secondary,
                    wide ? sidebar - 24 * u : width / 5.0f, wide ? 64 * u : tab_height, 10 + i);
            ns_background(tab, i == model->selected ? selected : bg);
        }
    }
    float inner_width = width - sidebar;
    void *scroll = ns_create(u"ScrollView", body, NS_SCROLL);
    ns_size(scroll, inner_width, height - 64 * u - tab_height);
    void *items = ns_flex(scroll, 0, 0);
    float content_height = 16 * u;
    ns_size(items, inner_width, -1);
    ns_padding(items, 12 * u);
    if (model->mode == 1) {
        void *editor = ns_create(u"EditText", items, NS_EDIT);
        ns_text(editor, model->draft, 24 * u, fg, 0x13);
        ns_size(editor, inner_width - 24 * u, 64 * u);
        ns_padding(editor, 16 * u);
        ns_background(editor, surface);
        NS_FN(void (*)(void *, int), 0x3a87c0)(editor, 1);
        NS_FN(void (*)(void *, int), 0x4f85a8)(editor, 1);
        ns_click(editor, 20 + model->input_type);
        content_height += 72 * u;
    }
    for (int i = 0; i < model->count; i++) {
        int kind = model->kinds[i];
        float row_height = (kind == 1 ? 42 : kind == 4 ? 148 : kind == 5 ? 36 : 64) * u;
        if (kind == 4) {
            int count = 0, segment = 0, lines = 0;
            while (model->titles[i][count]) {
                if (model->titles[i][count++] == '\n') {
                    lines += 1 + (int)(segment * 19 * u / (inner_width - 56 * u));
                    segment = 0;
                } else segment++;
            }
            lines += 1 + (int)(segment * 19 * u / (inner_width - 56 * u));
            row_height = (32 + 28 * lines) * u;
        }
        void *row = ns_flex(items, 0, 1);
        ns_size(row, inner_width - 24 * u, row_height);
        NS_FN(void (*)(void *, int, float), 0x6be664)(ns_node(row), 3, 6 * u);
        ns_background(row, (kind == 1 || kind == 4 || kind == 5) ? bg : surface);
        float help_width = model->help[i] ? 52 * u : 0;
        float value_width = model->values[i][0] ? (model->mode == 2 ? inner_width * 0.6f : (wide ? 180 : 100) * u) : 0;
        void *label = ns_label(row, model->titles[i], kind == 1 ? 16 : 19,
                kind == 3 ? blue : kind == 1 ? secondary : kind == 5 ? 0xffe95464 : fg,
                inner_width - 24 * u - help_width - value_width, row_height, model->actions[i]);
        ns_padding(label, 16 * u);
        if (kind == 4) NS_FN(void (*)(void *, int), 0x3a867c)(label, 24);
        NS_FN(void (*)(void *, int), 0x3a7f94)(label, kind == 3 ? 0x11 : 0x13);
        if (kind == 2) {
            ns_switch(row, model->values[i][0] == 0x5f00, model->actions[i], value_width, row_height);
        } else if (value_width) {
            void *value = ns_label(row, model->values[i], 18, blue, value_width, row_height, model->actions[i]);
            NS_FN(void (*)(void *, int), 0x3a87c0)(value, 1);
        }
        if (help_width) ns_label(row, u"ⓘ", 22, secondary, help_width, row_height, model->help[i]);
        content_height += row_height + 6 * u;
    }
    ns_size(items, inner_width, content_height + 16 * u);
    NS_FN(void (*)(void *, void *), 0x676a88)(scroll, items);
    NS_FN(void (*)(void *, int), 0x625ce0)(root, 0);
    if (top) NS_FN(void (*)(void *, int), 0x675c24)(scroll, top);
}
