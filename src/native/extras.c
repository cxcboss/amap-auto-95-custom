#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <link.h>
#include <dirent.h>
#include <sys/resource.h>

#include "native_settings.inc"

static void *current_fragment(void *lib, void *frame, int *page_state) {
    void **instance = dlsym(lib, "_ZN16GNS_CGMAINPACKET12CGMainPacket11m_pInstanceE");
    void *packet = instance ? *instance : 0;
    void *manager = packet ? *(void **)((char *)packet + 0x258) : 0;
    void *context = manager ? *(void **)((char *)manager + 0x18) : 0;
    void *fragment = 0;
    int state = context ? *(int *)((char *)context + 0x58) : -1;
    if (page_state) *page_state = state;
    if (state == 6) {
        void *(*top)(void *, int) = frame ? dlsym(frame, "_ZN9GNS_FRAME18CGFragmentActivity14getTopFragmentEi") : 0;
        if (top) fragment = top(manager, -1);
    }
    if (state == 2) {
        void *view = *(void **)((char *)context + 0x50);
        int (*getId)(void *) = frame ? dlsym(frame, "_ZNK9GNS_FRAME6CGView5getIdEv") : 0;
        if (view && getId) {
            void *(*getFragment)(void *, int) = (*(void ***)manager)[0x88 / sizeof(void *)];
            fragment = getFragment(manager, getId(view));
        }
    }
    return fragment;
}

// Private object layout belongs to the bundled 9.5.0.600013 arm64 engine.
JNIEXPORT jstring JNICALL Java_floatapp_floatset_SettingsEntry_nativePageType(JNIEnv *env, jclass cls) {
    void *lib = dlopen("libautonavi.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) return (*env)->NewStringUTF(env, "");
    void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
    int state = -1;
    void *fragment = current_fragment(lib, frame, &state);
    const char *name = "";
    if (fragment) {
        void **vtable = *(void ***)fragment;
        void **type = (void **)vtable[-1];
        name = (const char *)type[1];
    }
    char result[384];
    snprintf(result, sizeof(result), "%d:%s", state, name);
    jstring value = (*env)->NewStringUTF(env, result);
    if (frame) dlclose(frame);
    dlclose(lib);
    return value;
}

// asl::Runnable ABI in the bundled engine: run, onCancel, destructor, deleting destructor.
typedef struct {
    void **vtable;
    void *message_ctrl;
    void *table[6];
    void (*base_destructor)(void *);
    jint action;
    JavaVM *vm;
    jobject target;
    jmethodID callback;
    SettingsModel *model;
} PanelTask;

static void *navigation_card(void *view) {
    if (*(int *)((char *)view + 0x578) == 0x4070822b) return view;
    for (void *child = *(void **)((char *)view + 0x48); child;
            child = *(void **)((char *)child + 0x10)) {
        void *card = navigation_card(child);
        if (card) return card;
    }
    return 0;
}

static void *cruise_speed(void *view, int (*appear)(void *)) {
    void **vtable = *(void ***)view;
    const char *type = (const char *)((void **)vtable[-1])[1];
    if (strcmp(type, "35CGComponentTripMapElementsSpeedView") == 0 && appear(view))
        return view;
    for (void *child = *(void **)((char *)view + 0x48); child;
            child = *(void **)((char *)child + 0x10)) {
        void *speed = cruise_speed(child, appear);
        if (speed) return speed;
    }
    return 0;
}

static void panel_task_callback(PanelTask *task, int deliver, int night, int right, int top, const char *page) {
    if (!task->target) return;
    JNIEnv *env;
    int attach = (*task->vm)->GetEnv(task->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attach) (*task->vm)->AttachCurrentThread(task->vm, &env, 0);
    if (deliver) {
        if (task->action == 6) {
            jstring snapshot = (*env)->NewStringUTF(env, page);
            (*env)->CallVoidMethod(env, task->target, task->callback, snapshot,
                    (jboolean)night, (jint)right, (jint)top);
            (*env)->DeleteLocalRef(env, snapshot);
        } else
            (*env)->CallVoidMethod(env, task->target, task->callback, (jboolean)night);
    }
    (*env)->DeleteGlobalRef(env, task->target);
    task->target = 0;
    if (attach) (*task->vm)->DetachCurrentThread(task->vm);
}

static void panel_task_run(PanelTask *task) {
    void *lib = dlopen("libautonavi.so", RTLD_NOW | RTLD_NOLOAD);
    void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
    int page_state = -1;
    void *fragment = lib && frame ? current_fragment(lib, frame, &page_state) : 0;
    int entry_changed = 0;
    if (task->action == 6) {
        const char *type = fragment ? (const char *)((void **)(*(void ***)fragment)[-1])[1] : "";
        void *(*context)(void *) = frame ? dlsym(frame, "_ZN9GNS_FRAME10CGFragment10getContextEv") : 0;
        int (*isNight)(void *) = frame ? dlsym(frame, "_ZNK9GNS_FRAME9CGContext7isNightEv") : 0;
        int night = fragment && context && isNight ? isNight(context(fragment)) : 0;
        int right = -1, top = -1;
        if (strcmp(type, "N3hmi15GFragmentCruiseE") == 0) {
            int (*appear)(void *) = dlsym(frame, "_ZNK9GNS_FRAME6CGView8isAppearEv");
            void *root = *(void **)((char *)fragment + 0x3b0);
            if (root && appear) {
                void *speed = cruise_speed(root, appear);
                if (speed) {
                    right = *(int *)((char *)speed + 0xc8) + *(int *)((char *)speed + 0xb8)
                            - *(int *)((char *)root + 0xc8);
                    top = *(int *)((char *)speed + 0xcc) - *(int *)((char *)root + 0xcc);
                }
            }
        }
        char page[384];
        snprintf(page, sizeof(page), "%d:%s", page_state, type);
        panel_task_callback(task, 1, night, right, top, page);
    } else if (fragment) {
        void **vtable = *(void ***)fragment;
        const char *name = (const char *)((void **)vtable[-1])[1];
        if (task->action == 4 || task->action == 5) {
            ns_render(fragment, frame, task->model);
        } else if (task->action <= 1 && strcmp(name, "N3hmi15GFragmentCruiseE") == 0) {
            void (*finish)(void *) = dlsym(frame, "_ZN9GNS_FRAME10CGFragment6finishEv");
            if (finish) {
                // GFrame ViewPager tests bit 0 of this exported flag for animated completion.
                // Scope the immediate switch to this one UI-thread entry action.
                unsigned int *animation = dlsym(frame,
                        "_ZN9GNS_FRAME13CGFrameConfig15s_flagAnimationE");
                unsigned int previous = animation ? *animation : 0;
                if (animation) *animation = previous & ~1u;
                void *(*station)(void *) = dlsym(frame,
                        "_ZN9GNS_FRAME13CGWorkStation14GetWorkStationEPNS_6CGViewE");
                void (*vsync)(void *) = dlsym(frame,
                        "_ZN9GNS_FRAME13CGWorkStation16RequestVsyncOnceEv");
                void *root = *(void **)((char *)fragment + 0x3b0);
                void *workstation = root && station ? station(root) : 0;
                finish(fragment);
                void *index = current_fragment(lib, frame, 0);
                const char *type = index ? (const char *)((void **)(*(void ***)index)[-1])[1] : "";
                entry_changed = strcmp(type, "15CGIndexFragment") == 0;
                if (task->action == 1 && entry_changed) {
                    void (*search)(void *, void *) = dlsym(lib,
                            "_ZN15CGIndexFragment16onSearchBarClickEPN9GNS_FRAME6CGViewE");
                    if (search) search(index, 0);
                    else entry_changed = 0;
                }
                if (animation) *animation = previous;
                if (workstation && vsync) vsync(workstation);
            }
        } else if (task->action == 2 && strstr(name, "CGNaviFragment")) {
            void (*axis)(void *, float, float) = dlsym(frame,
                    "_ZN9GNS_FRAME6CGView12SetAxisRatioEff");
            void (*scaleX)(void *, float) = dlsym(frame, "_ZN9GNS_FRAME6CGView9SetScaleXEf");
            void (*scaleY)(void *, float) = dlsym(frame, "_ZN9GNS_FRAME6CGView9SetScaleYEf");
            // The card is in the normal-style child fragment; ordinary findViewById stops there.
            void *root = *(void **)((char *)fragment + 0x3b0);
            void *card = root ? navigation_card(root) : 0;
            if (card && axis && scaleX && scaleY) {
                axis(card, 0, 0);
                scaleX(card, 0.9f);
                scaleY(card, 0.9f);
            }
        }
    }
    if (task->action <= 1) panel_task_callback(task, 1, entry_changed, 0, 0, 0);
    if (frame) dlclose(frame);
    if (lib) dlclose(lib);
}

static void panel_task_delete(PanelTask *task) {
    panel_task_callback(task, 0, 0, 0, 0, 0);
    ns_free_model(task->model);
    task->base_destructor(task);
    free(task);
}

static jboolean post_panel_task(JNIEnv *env, jint action, jobject target, SettingsModel *model) {
    void *frame = dlopen("libGFrame.so", RTLD_NOW | RTLD_NOLOAD);
    void *(*appInstance)(void) = frame ? dlsym(frame, "_ZN9GNS_FRAME5CGApp11getInstanceEv") : 0;
    void *(*station)(void *, int) = frame ? dlsym(frame, "_ZNK9GNS_FRAME5CGApp14getWorkStationEi") : 0;
    void (*post)(void *, void *, unsigned long, unsigned long, void *) = frame ? dlsym(frame, "_ZN9GNS_FRAME17CGFrameSimulation23postUserRunnableDelayedEPN3asl8RunnableEmmPN3GPI12ScopedRefptrINS4_11MessageCtrlEEE") : 0;
    void (*construct)(void *) = frame ? dlsym(frame, "_ZN3asl8RunnableC2Ev") : 0;
    void *app = appInstance ? appInstance() : 0;
    void *registry = app ? *(void **)((char *)app + 0x48) : 0;
    void *first = registry ? *(void **)((char *)registry + 0xa8) : 0;
    void *end = registry ? *(void **)((char *)registry + 0xb0) : 0;
    int window_id = first && first != end ? *(int *)((char *)first + 8) : -1;
    void *workstation = app && station && window_id >= 0 ? station(app, window_id) : 0;
    jboolean queued = JNI_FALSE;
    if (workstation && post && construct) {
        PanelTask *task = calloc(1, sizeof(PanelTask));
        construct(task);
        memcpy(task->table, task->vtable - 2, sizeof(task->table));
        task->base_destructor = task->vtable[2];
        task->action = action;
        task->model = model;
        if (target) {
            (*env)->GetJavaVM(env, &task->vm);
            task->target = (*env)->NewGlobalRef(env, target);
            jclass type = (*env)->GetObjectClass(env, target);
            if (task->action == 6)
                task->callback = (*env)->GetMethodID(env, type, "pageChanged", "(Ljava/lang/String;ZII)V");
            else
                task->callback = (*env)->GetMethodID(env, type, "entryFinished", "(Z)V");
            (*env)->DeleteLocalRef(env, type);
        }
        task->table[2] = panel_task_run;
        task->table[5] = panel_task_delete;
        task->vtable = task->table + 2;
        post(workstation, task, 0, 0, 0);
        queued = JNI_TRUE;
    }
    if (frame) dlclose(frame);
    return queued;
}

JNIEXPORT jboolean JNICALL Java_floatapp_floatset_SettingsEntry_nativeOpenEntry(JNIEnv *env, jclass cls, jint action, jobject target) {
    return post_panel_task(env, action, target, 0);
}

JNIEXPORT void JNICALL Java_floatapp_floatset_NativeSettings_nativeBind(JNIEnv *env, jclass cls) {
    (*env)->GetJavaVM(env, &ns_vm);
    if (ns_class) (*env)->DeleteGlobalRef(env, ns_class);
    ns_class = (*env)->NewGlobalRef(env, cls);
    ns_action = (*env)->GetStaticMethodID(env, cls, "onAction", "(ILjava/lang/String;)V");
}
JNIEXPORT void JNICALL Java_floatapp_floatset_NativeSettings_nativeEnsure(JNIEnv *env, jclass cls) {
    post_panel_task(env, 4, 0, 0);
}
JNIEXPORT void JNICALL Java_floatapp_floatset_NativeSettings_nativeRender(JNIEnv *env, jclass cls,
        jboolean visible, jint selected, jint mode, jboolean reset, jstring title, jstring draft, jint input_type,
        jobjectArray titles, jobjectArray values, jintArray kinds, jintArray actions, jintArray help) {
    SettingsModel *model = calloc(1, sizeof(*model));
    model->visible = visible; model->selected = selected; model->mode = mode; model->reset = reset; model->input_type = input_type;
    model->title = ns_copy_string(env, title); model->draft = ns_copy_string(env, draft);
    model->count = (*env)->GetArrayLength(env, titles);
    int n = model->count;
    model->titles = calloc(n, sizeof(void *)); model->values = calloc(n, sizeof(void *));
    model->kinds = calloc(n, sizeof(jint)); model->actions = calloc(n, sizeof(jint)); model->help = calloc(n, sizeof(jint));
    for (int i = 0; i < n; i++) {
        jstring key = (*env)->GetObjectArrayElement(env, titles, i);
        jstring val = (*env)->GetObjectArrayElement(env, values, i);
        model->titles[i] = ns_copy_string(env, key); model->values[i] = ns_copy_string(env, val);
        (*env)->DeleteLocalRef(env, key); (*env)->DeleteLocalRef(env, val);
    }
    (*env)->GetIntArrayRegion(env, kinds, 0, n, model->kinds);
    (*env)->GetIntArrayRegion(env, actions, 0, n, model->actions);
    (*env)->GetIntArrayRegion(env, help, 0, n, model->help);
    if (!post_panel_task(env, 5, 0, model)) ns_free_model(model);
}

// Idle flag read by the patched MapViewFPSController::UpdateFPS. It lives in the zero-filled
// tail of libAutoDice's last RW page (VA 0x3dfbf00, past .bss end 0x3dfb428).
static int find_autodice(struct dl_phdr_info *info, size_t size, void *data) {
    const char *name = info->dlpi_name;
    size_t length = name ? strlen(name) : 0;
    if (length >= 14 && strcmp(name + length - 14, "libAutoDice.so") == 0) {
        *(uintptr_t *)data = info->dlpi_addr;
        return 1;
    }
    return 0;
}

JNIEXPORT void JNICALL Java_floatapp_floatset_IdleRender_nativeSetIdle(JNIEnv *env, jclass cls, jboolean idle) {
    static volatile int *flag;
    if (!flag) {
        uintptr_t base = 0;
        dl_iterate_phdr(find_autodice, &base);
        if (!base) return;
        flag = (volatile int *)(base + 0x3dfbf00);
    }
    *flag = idle ? 1 : 0;
}

// Voice synthesis (ALQueMgr*) runs in bursts when navigation starts; keep it from
// competing with the map render threads on low-end CPUs.
JNIEXPORT void JNICALL Java_floatapp_floatset_IdleRender_nativeLowerVoicePriority(JNIEnv *env, jclass cls) {
    DIR *tasks = opendir("/proc/self/task");
    if (!tasks) return;
    for (struct dirent *entry; (entry = readdir(tasks));) {
        int tid = atoi(entry->d_name);
        if (tid <= 0) continue;
        char path[64], name[32] = "";
        snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
        FILE *comm = fopen(path, "re");
        if (!comm) continue;
        if (fgets(name, sizeof(name), comm) && strncmp(name, "ALQueMgr", 8) == 0)
            setpriority(PRIO_PROCESS, tid, 10);
        fclose(comm);
    }
    closedir(tasks);
}
