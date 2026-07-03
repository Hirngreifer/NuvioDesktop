// Linux player bridge for Nuvio Desktop.
//
// Unlike the Windows/macOS bridges this does NOT create a native child window
// or a WebView overlay. Video frames are rendered through libmpv's software
// render API into a caller-provided buffer; the Compose UI draws them and
// renders its own controls on top (the shared PlayerControls from commonMain).
//
// libmpv is loaded at runtime via dlopen (same pattern as the Windows bridge
// uses LoadLibrary), so the packaged app has no hard link-time dependency.
//
// Threading: the mpv client API is thread-safe. render() must always be called
// from the same thread (the Kotlin frame loop); all other calls may come from
// any thread.
#include <dlfcn.h>
#include <jni.h>
#include <locale.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Minimal subset of the libmpv ABI (client.h / render.h) so we can build
// without mpv headers installed. Values match the stable libmpv 2.x ABI.
typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;

typedef enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
} mpv_format;

typedef struct mpv_event {
    int event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;

typedef struct mpv_event_end_file {
    int reason;
    int error;
} mpv_event_end_file;

enum {
    MPV_EVENT_NONE = 0,
    MPV_EVENT_SHUTDOWN = 1,
    MPV_EVENT_START_FILE = 6,
    MPV_EVENT_END_FILE = 7,
    MPV_EVENT_FILE_LOADED = 8,
};

enum {
    MPV_END_FILE_REASON_EOF = 0,
    MPV_END_FILE_REASON_ERROR = 4,
};

typedef struct mpv_render_param {
    int type;
    void *data;
} mpv_render_param;

enum {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    // Values verified against mpv render.h (libmpv 2.x stable ABI).
    MPV_RENDER_PARAM_SW_SIZE = 17,
    MPV_RENDER_PARAM_SW_FORMAT = 18,
    MPV_RENDER_PARAM_SW_STRIDE = 19,
    MPV_RENDER_PARAM_SW_POINTER = 20,
};

#define MPV_RENDER_API_TYPE_SW "sw"
#define MPV_RENDER_UPDATE_FRAME 1

typedef struct {
    void *lib;
    mpv_handle *(*create)(void);
    int (*initialize)(mpv_handle *);
    void (*terminate_destroy)(mpv_handle *);
    int (*command)(mpv_handle *, const char **);
    int (*set_option_string)(mpv_handle *, const char *, const char *);
    int (*set_property_string)(mpv_handle *, const char *, const char *);
    int (*set_property)(mpv_handle *, const char *, mpv_format, void *);
    int (*get_property)(mpv_handle *, const char *, mpv_format, void *);
    void (*free_ptr)(void *);
    mpv_event *(*wait_event)(mpv_handle *, double);
    const char *(*error_string)(int);
    int (*render_context_create)(mpv_render_context **, mpv_handle *, mpv_render_param *);
    uint64_t (*render_context_update)(mpv_render_context *);
    int (*render_context_render)(mpv_render_context *, mpv_render_param *);
    void (*render_context_free)(mpv_render_context *);
} MpvApi;

static MpvApi api;
static int api_loaded = 0;
static pthread_mutex_t api_mutex = PTHREAD_MUTEX_INITIALIZER;

static int load_mpv_api(void) {
    pthread_mutex_lock(&api_mutex);
    if (api_loaded) {
        pthread_mutex_unlock(&api_mutex);
        return 1;
    }
    const char *candidates[] = {"libmpv.so.2", "libmpv.so.1", "libmpv.so", NULL};
    for (int i = 0; candidates[i] && !api.lib; i++) {
        api.lib = dlopen(candidates[i], RTLD_NOW | RTLD_GLOBAL);
    }
    if (!api.lib) {
        fprintf(stderr, "nuvio-linux-bridge: libmpv not found (tried libmpv.so.2/.1)\n");
        pthread_mutex_unlock(&api_mutex);
        return 0;
    }
#define LOAD_SYM(field, name)                                        \
    do {                                                             \
        *(void **)(&api.field) = dlsym(api.lib, name);               \
        if (!api.field) {                                            \
            fprintf(stderr, "nuvio-linux-bridge: missing symbol %s\n", name); \
            pthread_mutex_unlock(&api_mutex);                        \
            return 0;                                                \
        }                                                            \
    } while (0)
    LOAD_SYM(create, "mpv_create");
    LOAD_SYM(initialize, "mpv_initialize");
    LOAD_SYM(terminate_destroy, "mpv_terminate_destroy");
    LOAD_SYM(command, "mpv_command");
    LOAD_SYM(set_option_string, "mpv_set_option_string");
    LOAD_SYM(set_property_string, "mpv_set_property_string");
    LOAD_SYM(set_property, "mpv_set_property");
    LOAD_SYM(get_property, "mpv_get_property");
    LOAD_SYM(free_ptr, "mpv_free");
    LOAD_SYM(wait_event, "mpv_wait_event");
    LOAD_SYM(error_string, "mpv_error_string");
    LOAD_SYM(render_context_create, "mpv_render_context_create");
    LOAD_SYM(render_context_update, "mpv_render_context_update");
    LOAD_SYM(render_context_render, "mpv_render_context_render");
    LOAD_SYM(render_context_free, "mpv_render_context_free");
#undef LOAD_SYM
    api_loaded = 1;
    pthread_mutex_unlock(&api_mutex);
    return 1;
}

typedef struct {
    mpv_handle *mpv;
    mpv_render_context *rctx;
    pthread_mutex_t state_mutex;
    int ended;          // END_FILE with reason EOF
    int file_loaded;    // FILE_LOADED seen
    char error_message[512];
} Player;

// Drains the mpv event queue and folds interesting events into player state.
static void drain_events(Player *p) {
    while (1) {
        mpv_event *ev = api.wait_event(p->mpv, 0);
        if (!ev || ev->event_id == MPV_EVENT_NONE) break;
        pthread_mutex_lock(&p->state_mutex);
        switch (ev->event_id) {
            case MPV_EVENT_FILE_LOADED:
                p->file_loaded = 1;
                p->ended = 0;
                break;
            case MPV_EVENT_START_FILE:
                p->ended = 0;
                break;
            case MPV_EVENT_END_FILE: {
                mpv_event_end_file *ef = (mpv_event_end_file *)ev->data;
                if (ef && ef->reason == MPV_END_FILE_REASON_ERROR) {
                    snprintf(p->error_message, sizeof(p->error_message),
                             "Playback failed: %s", api.error_string(ef->error));
                } else if (ef && ef->reason == MPV_END_FILE_REASON_EOF) {
                    p->ended = 1;
                }
                break;
            }
            default:
                break;
        }
        pthread_mutex_unlock(&p->state_mutex);
    }
}

static Player *player_from_handle(jlong handle) {
    return (Player *)(intptr_t)handle;
}

static double get_double(Player *p, const char *name, double fallback) {
    double v = fallback;
    if (api.get_property(p->mpv, name, MPV_FORMAT_DOUBLE, &v) < 0) return fallback;
    return v;
}

static int get_flag(Player *p, const char *name, int fallback) {
    int v = fallback;
    if (api.get_property(p->mpv, name, MPV_FORMAT_FLAG, &v) < 0) return fallback;
    return v;
}

#define BRIDGE_FN(ret, name) \
    JNIEXPORT ret JNICALL Java_com_nuvio_app_features_player_desktop_LinuxPlayerBridge_##name

BRIDGE_FN(jlong, create)(JNIEnv *env, jclass cls, jstring jurl, jobjectArray jheaderLines,
                         jboolean playWhenReady, jlong initialPositionMs, jstring jhwdec) {
    // libmpv requires LC_NUMERIC=C; the JVM applies the system locale on
    // startup (e.g. de_DE.UTF-8), which makes mpv_create() return NULL.
    setlocale(LC_NUMERIC, "C");
    if (!load_mpv_api()) return 0;

    Player *p = calloc(1, sizeof(Player));
    pthread_mutex_init(&p->state_mutex, NULL);
    p->mpv = api.create();
    if (!p->mpv) {
        free(p);
        return 0;
    }

    api.set_option_string(p->mpv, "vo", "libmpv");
    api.set_option_string(p->mpv, "keep-open", "yes");
    api.set_option_string(p->mpv, "idle", "yes");
    api.set_option_string(p->mpv, "input-default-bindings", "no");
    api.set_option_string(p->mpv, "osc", "no");
    api.set_option_string(p->mpv, "terminal", "no");
    // Network resilience for streaming sources
    api.set_option_string(p->mpv, "cache", "yes");
    api.set_option_string(p->mpv, "demuxer-max-bytes", "128MiB");
    api.set_option_string(p->mpv, "demuxer-max-back-bytes", "64MiB");

    const char *hwdec = (*env)->GetStringUTFChars(env, jhwdec, NULL);
    api.set_option_string(p->mpv, "hwdec", hwdec && hwdec[0] ? hwdec : "auto-copy");
    (*env)->ReleaseStringUTFChars(env, jhwdec, hwdec);

    // HTTP headers arrive as "Key: Value" lines; mpv wants a comma-separated
    // list option. User-Agent gets its own option when present.
    jsize headerCount = jheaderLines ? (*env)->GetArrayLength(env, jheaderLines) : 0;
    if (headerCount > 0) {
        size_t cap = 4096;
        char *joined = calloc(1, cap);
        for (jsize i = 0; i < headerCount; i++) {
            jstring jline = (jstring)(*env)->GetObjectArrayElement(env, jheaderLines, i);
            const char *line = (*env)->GetStringUTFChars(env, jline, NULL);
            if (strncasecmp(line, "user-agent:", 11) == 0) {
                const char *ua = line + 11;
                while (*ua == ' ') ua++;
                api.set_option_string(p->mpv, "user-agent", ua);
            } else {
                size_t need = strlen(joined) + strlen(line) + 2;
                if (need > cap) {
                    cap = need * 2;
                    joined = realloc(joined, cap);
                }
                if (joined[0]) strcat(joined, ",");
                strcat(joined, line);
            }
            (*env)->ReleaseStringUTFChars(env, jline, line);
            (*env)->DeleteLocalRef(env, jline);
        }
        if (joined[0]) api.set_option_string(p->mpv, "http-header-fields", joined);
        free(joined);
    }

    if (initialPositionMs > 0) {
        char start[64];
        snprintf(start, sizeof(start), "%.3f", (double)initialPositionMs / 1000.0);
        api.set_option_string(p->mpv, "start", start);
    }
    api.set_option_string(p->mpv, "pause", playWhenReady ? "no" : "yes");

    if (api.initialize(p->mpv) < 0) {
        api.terminate_destroy(p->mpv);
        free(p);
        return 0;
    }

    mpv_render_param cparams[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    if (api.render_context_create(&p->rctx, p->mpv, cparams) < 0) {
        api.terminate_destroy(p->mpv);
        free(p);
        return 0;
    }

    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    const char *cmd[] = {"loadfile", url, NULL};
    api.command(p->mpv, cmd);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
    return (jlong)(intptr_t)p;
}

BRIDGE_FN(void, dispose)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    if (p->rctx) api.render_context_free(p->rctx);
    if (p->mpv) api.terminate_destroy(p->mpv);
    pthread_mutex_destroy(&p->state_mutex);
    free(p);
}

// Renders the newest video frame into the direct buffer (BGRA, stride=w*4).
// Returns 1 if a new frame was written, 0 if nothing changed, -1 on error.
BRIDGE_FN(jint, render)(JNIEnv *env, jclass cls, jlong handle, jobject jbuf, jint w, jint h) {
    Player *p = player_from_handle(handle);
    if (!p || !p->rctx) return -1;
    drain_events(p);

    uint64_t flags = api.render_context_update(p->rctx);
    if (!(flags & MPV_RENDER_UPDATE_FRAME)) return 0;

    void *pixels = (*env)->GetDirectBufferAddress(env, jbuf);
    if (!pixels) return -1;
    int size[2] = {w, h};
    size_t stride = (size_t)w * 4;
    mpv_render_param rparams[] = {
        {MPV_RENDER_PARAM_SW_SIZE, size},
        {MPV_RENDER_PARAM_SW_FORMAT, "bgr0"}, // matches Skia BGRA_8888 + OPAQUE
        {MPV_RENDER_PARAM_SW_STRIDE, &stride},
        {MPV_RENDER_PARAM_SW_POINTER, pixels},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    return api.render_context_render(p->rctx, rparams) >= 0 ? 1 : -1;
}

BRIDGE_FN(void, setPaused)(JNIEnv *env, jclass cls, jlong handle, jboolean paused) {
    Player *p = player_from_handle(handle);
    if (p) api.set_property_string(p->mpv, "pause", paused ? "yes" : "no");
}

BRIDGE_FN(jboolean, isPaused)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p && get_flag(p, "pause", 1) ? JNI_TRUE : JNI_FALSE;
}

BRIDGE_FN(void, seekTo)(JNIEnv *env, jclass cls, jlong handle, jlong positionMs) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    char pos[64];
    snprintf(pos, sizeof(pos), "%.3f", (double)positionMs / 1000.0);
    const char *cmd[] = {"seek", pos, "absolute+exact", NULL};
    api.command(p->mpv, cmd);
}

BRIDGE_FN(void, seekBy)(JNIEnv *env, jclass cls, jlong handle, jlong offsetMs) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    char off[64];
    snprintf(off, sizeof(off), "%.3f", (double)offsetMs / 1000.0);
    const char *cmd[] = {"seek", off, "relative+exact", NULL};
    api.command(p->mpv, cmd);
}

BRIDGE_FN(void, setSpeed)(JNIEnv *env, jclass cls, jlong handle, jfloat speed) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    double v = speed;
    api.set_property(p->mpv, "speed", MPV_FORMAT_DOUBLE, &v);
}

BRIDGE_FN(jfloat, speed)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p ? (jfloat)get_double(p, "speed", 1.0) : 1.0f;
}

BRIDGE_FN(void, setVolume)(JNIEnv *env, jclass cls, jlong handle, jfloat level) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    double v = (double)level * 100.0;
    api.set_property(p->mpv, "volume", MPV_FORMAT_DOUBLE, &v);
}

BRIDGE_FN(jfloat, volume)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p ? (jfloat)(get_double(p, "volume", 100.0) / 100.0) : 1.0f;
}

BRIDGE_FN(jlong, durationMs)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p ? (jlong)(get_double(p, "duration", 0.0) * 1000.0) : 0;
}

BRIDGE_FN(jlong, positionMs)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p ? (jlong)(get_double(p, "time-pos", 0.0) * 1000.0) : 0;
}

BRIDGE_FN(jlong, bufferedPositionMs)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p ? (jlong)(get_double(p, "demuxer-cache-time", 0.0) * 1000.0) : 0;
}

BRIDGE_FN(jboolean, isLoading)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return JNI_FALSE;
    pthread_mutex_lock(&p->state_mutex);
    int loaded = p->file_loaded;
    pthread_mutex_unlock(&p->state_mutex);
    if (!loaded) return JNI_TRUE;
    return get_flag(p, "paused-for-cache", 0) || get_flag(p, "seeking", 0) ? JNI_TRUE : JNI_FALSE;
}

BRIDGE_FN(jboolean, isEnded)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return JNI_FALSE;
    if (get_flag(p, "eof-reached", 0)) return JNI_TRUE;
    pthread_mutex_lock(&p->state_mutex);
    int ended = p->ended;
    pthread_mutex_unlock(&p->state_mutex);
    return ended ? JNI_TRUE : JNI_FALSE;
}

// mpv exposes track-list natively as JSON via the string property accessor —
// pass it through and let Kotlin parse it.
BRIDGE_FN(jstring, trackListJson)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return (*env)->NewStringUTF(env, "[]");
    char *json = NULL;
    if (api.get_property(p->mpv, "track-list", MPV_FORMAT_STRING, &json) < 0 || !json) {
        return (*env)->NewStringUTF(env, "[]");
    }
    jstring result = (*env)->NewStringUTF(env, json);
    api.free_ptr(json);
    return result;
}

BRIDGE_FN(void, selectAudioTrack)(JNIEnv *env, jclass cls, jlong handle, jint trackId) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    char id[32];
    if (trackId < 0) snprintf(id, sizeof(id), "no");
    else snprintf(id, sizeof(id), "%d", trackId);
    api.set_property_string(p->mpv, "aid", id);
}

BRIDGE_FN(void, selectSubtitleTrack)(JNIEnv *env, jclass cls, jlong handle, jint trackId) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    char id[32];
    if (trackId < 0) snprintf(id, sizeof(id), "no");
    else snprintf(id, sizeof(id), "%d", trackId);
    api.set_property_string(p->mpv, "sid", id);
}

BRIDGE_FN(void, addSubtitleUrl)(JNIEnv *env, jclass cls, jlong handle, jstring jurl) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    const char *cmd[] = {"sub-add", url, "select", NULL};
    api.command(p->mpv, cmd);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
}

BRIDGE_FN(void, setMuted)(JNIEnv *env, jclass cls, jlong handle, jboolean muted) {
    Player *p = player_from_handle(handle);
    if (p) api.set_property_string(p->mpv, "mute", muted ? "yes" : "no");
}

// Maps PlayerResizeMode ordinals: 0=Fit, 1=Fill, 2=Zoom, 3=Stretch.
BRIDGE_FN(void, setResizeMode)(JNIEnv *env, jclass cls, jlong handle, jint mode) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    switch (mode) {
        case 1: // Fill
        case 2: // Zoom — crop to fill
            api.set_property_string(p->mpv, "keepaspect", "yes");
            api.set_property_string(p->mpv, "panscan", "1.0");
            break;
        case 3: // Stretch
            api.set_property_string(p->mpv, "keepaspect", "no");
            api.set_property_string(p->mpv, "panscan", "0.0");
            break;
        default: // Fit
            api.set_property_string(p->mpv, "keepaspect", "yes");
            api.set_property_string(p->mpv, "panscan", "0.0");
            break;
    }
}

BRIDGE_FN(void, setSubtitleDelayMs)(JNIEnv *env, jclass cls, jlong handle, jint delayMs) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    double v = (double)delayMs / 1000.0;
    api.set_property(p->mpv, "sub-delay", MPV_FORMAT_DOUBLE, &v);
}

// Colors arrive as "#RRGGBB" / "#AARRGGBB" strings from the shared style state.
BRIDGE_FN(void, applySubtitleStyle)(JNIEnv *env, jclass cls, jlong handle,
                                    jstring jtextColor, jstring jbackgroundColor,
                                    jstring joutlineColor, jfloat outlineSize,
                                    jboolean bold, jfloat fontSize, jint subPos) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    const char *text = (*env)->GetStringUTFChars(env, jtextColor, NULL);
    const char *back = (*env)->GetStringUTFChars(env, jbackgroundColor, NULL);
    const char *outline = (*env)->GetStringUTFChars(env, joutlineColor, NULL);
    if (text && text[0]) api.set_property_string(p->mpv, "sub-color", text);
    if (back && back[0]) api.set_property_string(p->mpv, "sub-back-color", back);
    if (outline && outline[0]) api.set_property_string(p->mpv, "sub-border-color", outline);
    (*env)->ReleaseStringUTFChars(env, jtextColor, text);
    (*env)->ReleaseStringUTFChars(env, jbackgroundColor, back);
    (*env)->ReleaseStringUTFChars(env, joutlineColor, outline);

    double border = outlineSize;
    api.set_property(p->mpv, "sub-border-size", MPV_FORMAT_DOUBLE, &border);
    api.set_property_string(p->mpv, "sub-bold", bold ? "yes" : "no");
    double size = fontSize;
    api.set_property(p->mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &size);
    int64_t pos = subPos;
    api.set_property(p->mpv, "sub-pos", MPV_FORMAT_INT64, &pos);
}

BRIDGE_FN(jstring, lastErrorMessage)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return NULL;
    pthread_mutex_lock(&p->state_mutex);
    jstring result = p->error_message[0] ? (*env)->NewStringUTF(env, p->error_message) : NULL;
    p->error_message[0] = '\0';
    pthread_mutex_unlock(&p->state_mutex);
    return result;
}
