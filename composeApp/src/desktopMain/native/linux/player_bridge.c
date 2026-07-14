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
#include <fcntl.h>
#include <jni.h>
#include <locale.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static inline int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

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
    // Values verified against mpv render.h / render_gl.h (libmpv 2.x stable ABI).
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y = 4,
    MPV_RENDER_PARAM_X11_DISPLAY = 8,
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12,
    MPV_RENDER_PARAM_DRM_DISPLAY_V2 = 16,
    MPV_RENDER_PARAM_SW_SIZE = 17,
    MPV_RENDER_PARAM_SW_FORMAT = 18,
    MPV_RENDER_PARAM_SW_STRIDE = 19,
    MPV_RENDER_PARAM_SW_POINTER = 20,
};

typedef struct mpv_opengl_init_params {
    void *(*get_proc_address)(void *ctx, const char *name);
    void *get_proc_address_ctx;
} mpv_opengl_init_params;

typedef struct mpv_opengl_drm_params_v2 {
    int fd;
    int crtc_id;
    int connector_id;
    void **atomic_request_ptr;
    int render_fd;
} mpv_opengl_drm_params_v2;

typedef struct mpv_opengl_fbo {
    int fbo;
    int w, h;
    int internal_format;
} mpv_opengl_fbo;

#define MPV_RENDER_API_TYPE_OPENGL "opengl"
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

// ---------------------------------------------------------------------------
// Offscreen OpenGL backend (EGL pbuffer + FBO + glReadPixels).
//
// mpv does scaling/tone-mapping on the GPU and we read the finished frame
// back into the caller's buffer — same buffer contract as the software path,
// but fast enough for 4K60. Falls back to the software renderer whenever any
// step fails (no EGL, no GL, headless CI, ...).
// EGL/GL constants below are from the stable Khronos ABI.

typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef void *EGLContext;
typedef void *EGLSurface;
typedef int EGLint;
typedef unsigned EGLBoolean;
typedef unsigned GLenum;
typedef unsigned GLuint;
typedef int GLint;
typedef int GLsizei;

#define EGL_DEFAULT_DISPLAY ((void *)0)
#define EGL_NO_SURFACE ((void *)0)
#define EGL_SURFACE_TYPE 0x3033
#define EGL_PBUFFER_BIT 0x0001
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_BIT 0x0008
#define EGL_NONE 0x3038
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#define EGL_OPENGL_API 0x30A2
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#define EGL_CONTEXT_MINOR_VERSION 0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT 0x00000001

#define GL_TEXTURE_2D 0x0DE1
#define GL_RGBA8 0x8058
#define GL_RGBA 0x1908
#define GL_BGRA 0x80E1
#define GL_UNSIGNED_BYTE 0x1401
#define GL_FRAMEBUFFER 0x8D40
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_PACK_ALIGNMENT 0x0D05
#define GL_LINEAR 0x2601
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_STREAM_READ 0x88E1
#define GL_MAP_READ_BIT 0x0001
#define GL_SYNC_GPU_COMMANDS_COMPLETE 0x9117
#define GL_ALREADY_SIGNALED 0x911A
#define GL_TIMEOUT_EXPIRED 0x911B
#define GL_CONDITION_SATISFIED 0x911C
typedef ptrdiff_t GLsizeiptr;
typedef ptrdiff_t GLintptr;
typedef unsigned GLbitfield;
typedef void *GLsync;
typedef uint64_t GLuint64;

typedef struct {
    void *egl_lib;
    void *gl_lib;
    mpv_opengl_drm_params_v2 drm_params; // render node for VAAPI interop;
                                         // lifetime = render context
    EGLDisplay dpy;
    EGLContext ctx;
    EGLSurface surf;
    GLuint fbo;
    GLuint tex;
    int fbo_w, fbo_h;

    // Double-buffered pixel-pack buffers: glReadPixels into a PBO is an
    // asynchronous GPU-side detile/DMA, and mapping the *previous* frame's
    // PBO one call later avoids the pipeline stall entirely (readback into
    // client memory measured 50-230ms per frame on Iris Xe; see render()).
    GLuint pbo[2];
    int pbo_index;          // PBO the next readback goes into
    int pbo_pending[2];     // 1 = holds a frame not yet delivered
    GLsync pbo_fence[2];    // signaled when the pack transfer finished
    int pbo_w, pbo_h;
    GLenum last_wait_status; // debug: last glClientWaitSync result

    EGLDisplay (*GetDisplay)(void *);
    EGLBoolean (*Initialize)(EGLDisplay, EGLint *, EGLint *);
    EGLBoolean (*BindAPI)(unsigned);
    EGLBoolean (*ChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
    EGLSurface (*CreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
    EGLContext (*CreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
    EGLBoolean (*MakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    void *(*GetProcAddress)(const char *);
    EGLBoolean (*DestroyContext)(EGLDisplay, EGLContext);
    EGLBoolean (*DestroySurface)(EGLDisplay, EGLSurface);

    void (*GenTextures)(GLsizei, GLuint *);
    void (*BindTexture)(GLenum, GLuint);
    void (*TexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
    void (*TexParameteri)(GLenum, GLenum, GLint);
    void (*GenFramebuffers)(GLsizei, GLuint *);
    void (*BindFramebuffer)(GLenum, GLuint);
    void (*FramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
    GLenum (*CheckFramebufferStatus)(GLenum);
    void (*ReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *);
    void (*PixelStorei)(GLenum, GLint);
    void (*DeleteFramebuffers)(GLsizei, const GLuint *);
    void (*DeleteTextures)(GLsizei, const GLuint *);
    void (*GenBuffers)(GLsizei, GLuint *);
    void (*BindBuffer)(GLenum, GLuint);
    void (*BufferData)(GLenum, GLsizeiptr, const void *, GLenum);
    void *(*MapBufferRange)(GLenum, GLintptr, GLsizeiptr, GLbitfield);
    unsigned char (*UnmapBuffer)(GLenum);
    void (*DeleteBuffers)(GLsizei, const GLuint *);
    GLsync (*FenceSync)(GLenum, GLbitfield);
    GLenum (*ClientWaitSync)(GLsync, GLbitfield, GLuint64);
    void (*DeleteSync)(GLsync);
    void (*Flush)(void);
} GlBackend;

enum {
    BACKEND_PENDING = 0,
    BACKEND_GL = 1,
    BACKEND_SW = 2,
};

typedef struct {
    mpv_handle *mpv;
    mpv_render_context *rctx;
    pthread_mutex_t state_mutex;
    int backend;        // BACKEND_*; render context is created lazily on the
                        // frame thread because the GL context must be current
                        // on the thread that creates and uses it
    GlBackend gl;
    char *pending_url;  // loadfile is deferred until the render context
                        // exists — mpv drops video that starts without one
    int ended;          // END_FILE with reason EOF
    int file_loaded;    // FILE_LOADED seen
    char error_message[512];

    // NUVIO_LINUX_PERF=1: split timing of the GL render path, printed every
    // ~2s — separates mpv's own rendering from the glReadPixels readback.
    int perf_enabled;
    int64_t perf_window_start;
    int64_t perf_render_ns;
    int64_t perf_read_ns;
    int perf_frames;

    // hwdec watchdog: on some driver stacks rendering imported hwdec
    // surfaces stalls for hundreds of ms per frame (observed on Iris Xe /
    // iHD / i915 where every interop flavor rendered at 150-800ms while the
    // copy path rendered at 7-12ms). When we picked hwdec=auto ourselves,
    // watch the first render times and downgrade to auto-copy once.
    int hwdec_managed;      // we set hwdec=auto and may downgrade it
    int hwdec_watch_frames; // rendered frames counted so far
    int64_t hwdec_watch_ns; // their summed mpv render time
} Player;

// Watchdog tuning: skip the warm-up (shader compilation spikes), then judge
// a window of frames — or earlier once enough render time has accumulated,
// because pathological rendering delivers frames too slowly to ever fill the
// window. Healthy interop renders take single-digit ms even for 4K; the
// broken stacks measured 35ms (1080p) to 800ms (4K) per frame, so 25ms
// separates the two cleanly and stays below the 60fps frame budget.
enum {
    HWDEC_WATCH_SKIP = 5,
    HWDEC_WATCH_WINDOW = 30,
    HWDEC_WATCH_MIN_FRAMES = 5,
};
#define HWDEC_WATCH_AVG_LIMIT_NS 25000000LL
#define HWDEC_WATCH_MAX_TOTAL_NS 2000000000LL

static int mpv_render_context_create_checked(Player *p, mpv_render_param *params) {
    int r = api.render_context_create(&p->rctx, p->mpv, params);
    if (r < 0) p->rctx = NULL;
    return r < 0 ? -1 : 0;
}

static void *gl_resolve(void *ctx, const char *name) {
    GlBackend *gl = (GlBackend *)ctx;
    void *fn = gl->GetProcAddress ? gl->GetProcAddress(name) : NULL;
    if (!fn && gl->gl_lib) fn = dlsym(gl->gl_lib, name);
    return fn;
}

static int gl_backend_init(Player *p) {
    GlBackend *gl = &p->gl;
    const char *override = getenv("NUVIO_LINUX_RENDER");
    if (override && strcmp(override, "sw") == 0) return 0;

    gl->egl_lib = dlopen("libEGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    gl->gl_lib = dlopen("libGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!gl->egl_lib) return 0;

#define EGL_SYM(field, name) \
    do { *(void **)(&gl->field) = dlsym(gl->egl_lib, name); if (!gl->field) return 0; } while (0)
    EGL_SYM(GetDisplay, "eglGetDisplay");
    EGL_SYM(Initialize, "eglInitialize");
    EGL_SYM(BindAPI, "eglBindAPI");
    EGL_SYM(ChooseConfig, "eglChooseConfig");
    EGL_SYM(CreatePbufferSurface, "eglCreatePbufferSurface");
    EGL_SYM(CreateContext, "eglCreateContext");
    EGL_SYM(MakeCurrent, "eglMakeCurrent");
    EGL_SYM(GetProcAddress, "eglGetProcAddress");
    EGL_SYM(DestroyContext, "eglDestroyContext");
    EGL_SYM(DestroySurface, "eglDestroySurface");
#undef EGL_SYM

    gl->dpy = gl->GetDisplay(EGL_DEFAULT_DISPLAY);
    if (!gl->dpy) return 0;
    if (!gl->Initialize(gl->dpy, NULL, NULL)) return 0;
    if (!gl->BindAPI(EGL_OPENGL_API)) return 0;

    const EGLint cfg_attribs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_NONE,
    };
    EGLConfig config = NULL;
    EGLint num_configs = 0;
    if (!gl->ChooseConfig(gl->dpy, cfg_attribs, &config, 1, &num_configs) || num_configs < 1) return 0;

    const EGLint pbuf_attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    gl->surf = gl->CreatePbufferSurface(gl->dpy, config, pbuf_attribs);
    if (!gl->surf) return 0;

    // A legacy/compat context makes mpv's GLSL shaders fail to compile
    // (grey frames); request a 3.3 core profile explicitly.
    const EGLint ctx_attribs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 3,
        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
        EGL_NONE,
    };
    gl->ctx = gl->CreateContext(gl->dpy, config, NULL, ctx_attribs);
    if (!gl->ctx) return 0;
    if (!gl->MakeCurrent(gl->dpy, gl->surf, gl->surf, gl->ctx)) return 0;

#define GL_SYM(field, name) \
    do { *(void **)(&gl->field) = gl_resolve(gl, name); if (!gl->field) return 0; } while (0)
    GL_SYM(GenTextures, "glGenTextures");
    GL_SYM(BindTexture, "glBindTexture");
    GL_SYM(TexImage2D, "glTexImage2D");
    GL_SYM(TexParameteri, "glTexParameteri");
    GL_SYM(GenFramebuffers, "glGenFramebuffers");
    GL_SYM(BindFramebuffer, "glBindFramebuffer");
    GL_SYM(FramebufferTexture2D, "glFramebufferTexture2D");
    GL_SYM(CheckFramebufferStatus, "glCheckFramebufferStatus");
    GL_SYM(ReadPixels, "glReadPixels");
    GL_SYM(PixelStorei, "glPixelStorei");
    GL_SYM(DeleteFramebuffers, "glDeleteFramebuffers");
    GL_SYM(DeleteTextures, "glDeleteTextures");
    GL_SYM(GenBuffers, "glGenBuffers");
    GL_SYM(BindBuffer, "glBindBuffer");
    GL_SYM(BufferData, "glBufferData");
    GL_SYM(MapBufferRange, "glMapBufferRange");
    GL_SYM(UnmapBuffer, "glUnmapBuffer");
    GL_SYM(DeleteBuffers, "glDeleteBuffers");
    GL_SYM(FenceSync, "glFenceSync");
    GL_SYM(ClientWaitSync, "glClientWaitSync");
    GL_SYM(DeleteSync, "glDeleteSync");
    GL_SYM(Flush, "glFlush");
#undef GL_SYM

    // Which device actually renders decides everything about performance
    // (Mesa silently hands out llvmpipe when the real driver is unusable),
    // so always log it once.
    const unsigned char *(*GetString)(GLenum) = gl_resolve(gl, "glGetString");
    if (GetString) {
        const unsigned char *vendor = GetString(0x1F00 /* GL_VENDOR */);
        const unsigned char *renderer = GetString(0x1F01 /* GL_RENDERER */);
        fprintf(stderr, "nuvio-linux-bridge: GL device: %s / %s\n",
                vendor ? (const char *)vendor : "?",
                renderer ? (const char *)renderer : "?");
    }
    return 1;
}

static int gl_ensure_fbo(GlBackend *gl, int w, int h) {
    if (gl->fbo && gl->fbo_w == w && gl->fbo_h == h) return 1;
    if (gl->fbo) {
        gl->DeleteFramebuffers(1, &gl->fbo);
        gl->DeleteTextures(1, &gl->tex);
        gl->fbo = 0;
        gl->tex = 0;
    }
    gl->GenTextures(1, &gl->tex);
    gl->BindTexture(GL_TEXTURE_2D, gl->tex);
    gl->TexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    gl->TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    gl->TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    gl->GenFramebuffers(1, &gl->fbo);
    gl->BindFramebuffer(GL_FRAMEBUFFER, gl->fbo);
    gl->FramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, gl->tex, 0);
    if (gl->CheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return 0;
    gl->fbo_w = w;
    gl->fbo_h = h;
    return 1;
}

// (Re)allocates the two pack buffers for w*h RGBA frames. In-flight frames of
// the old size are dropped — after a resize their content is stale anyway.
static int gl_ensure_pbos(GlBackend *gl, int w, int h) {
    if (gl->pbo[0] && gl->pbo_w == w && gl->pbo_h == h) return 1;
    if (gl->pbo[0]) gl->DeleteBuffers(2, gl->pbo);
    gl->GenBuffers(2, gl->pbo);
    for (int i = 0; i < 2; i++) {
        gl->BindBuffer(GL_PIXEL_PACK_BUFFER, gl->pbo[i]);
        gl->BufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr)w * h * 4, NULL, GL_STREAM_READ);
        gl->pbo_pending[i] = 0;
        if (gl->pbo_fence[i]) {
            gl->DeleteSync(gl->pbo_fence[i]);
            gl->pbo_fence[i] = NULL;
        }
    }
    gl->BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    gl->pbo_index = 0;
    gl->pbo_w = w;
    gl->pbo_h = h;
    return 1;
}

// Returns 1 once the pack transfer into pbo[index] has completed. Never
// blocks: mapping a still-in-flight PBO would stall behind the entire GPU
// queue (decode traffic included), which is exactly what the PBO pipeline
// exists to avoid.
static int gl_pbo_ready(GlBackend *gl, int index) {
    if (!gl->pbo_pending[index]) return 0;
    if (!gl->pbo_fence[index]) return 1;
    GLenum r = gl->ClientWaitSync(gl->pbo_fence[index], 0, 0);
    gl->last_wait_status = r;
    if (r == GL_ALREADY_SIGNALED || r == GL_CONDITION_SATISFIED) {
        gl->DeleteSync(gl->pbo_fence[index]);
        gl->pbo_fence[index] = NULL;
        return 1;
    }
    return 0;
}

// Maps the given PBO and copies the finished frame into the caller's buffer.
// Returns 1 on success.
static int gl_deliver_pbo(GlBackend *gl, int index, void *pixels) {
    gl->BindBuffer(GL_PIXEL_PACK_BUFFER, gl->pbo[index]);
    size_t bytes = (size_t)gl->pbo_w * gl->pbo_h * 4;
    void *mapped = gl->MapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr)bytes, GL_MAP_READ_BIT);
    if (!mapped) {
        gl->BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        gl->pbo_pending[index] = 0;
        return 0;
    }
    memcpy(pixels, mapped, bytes);
    gl->UnmapBuffer(GL_PIXEL_PACK_BUFFER);
    gl->BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    gl->pbo_pending[index] = 0;
    return 1;
}

// Creates the mpv render context on the calling (frame) thread. Tries the
// OpenGL backend first, falls back to the software renderer.
static void init_render_backend(Player *p) {
    // mpv generates GLSL shaders with printf-formatted floats on this thread.
    // The JVM re-applies the system locale after create() ran, so a German
    // locale turns vec2(1920.0, ...) into vec2(1920,000000, ...) and every
    // shader fails to compile (uniformly grey frames). Pin a C numeric locale
    // to this thread; it outlives any process-wide setlocale() calls.
    locale_t numeric_c = newlocale(LC_NUMERIC_MASK, "C", (locale_t)0);
    if (numeric_c) uselocale(numeric_c);
    setlocale(LC_NUMERIC, "C");
    if (gl_backend_init(p)) {
        mpv_opengl_init_params gl_init = {
            .get_proc_address = gl_resolve,
            .get_proc_address_ctx = &p->gl,
        };
        // The offscreen pbuffer context has no native display, so mpv's
        // VAAPI interop cannot create a VA display on its own and hardware
        // decoding degrades to the copy modes (measured 16.7fps for 4K60
        // vaapi-copy vs 96fps native on Iris Xe). Hand mpv a DRM render
        // node: display-server independent, and unlike an XWayland-routed
        // X11 VA display it involves no cross-process buffer sync.
        mpv_opengl_drm_params_v2 *drm = &p->gl.drm_params;
        drm->fd = -1;
        drm->crtc_id = 0;
        drm->connector_id = 0;
        drm->atomic_request_ptr = NULL;
        drm->render_fd = -1;
        char node[32];
        for (int i = 128; i < 131 && drm->render_fd < 0; i++) {
            snprintf(node, sizeof(node), "/dev/dri/renderD%d", i);
            drm->render_fd = open(node, O_RDWR | O_CLOEXEC);
        }

        mpv_render_param params[4];
        int n = 0;
        params[n++] = (mpv_render_param){MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL};
        params[n++] = (mpv_render_param){MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init};
        if (drm->render_fd >= 0) {
            params[n++] = (mpv_render_param){MPV_RENDER_PARAM_DRM_DISPLAY_V2, drm};
        }
        params[n] = (mpv_render_param){MPV_RENDER_PARAM_INVALID, NULL};
        if (mpv_render_context_create_checked(p, params) == 0) {
            p->backend = BACKEND_GL;
            // With working interop the frames stay on the GPU end to end, so
            // native hwdec is strictly better than the copy modes here. The
            // property is set before the deferred loadfile runs; an explicit
            // NUVIO_MPV_HWDEC keeps the last word.
            const char *env_hwdec = getenv("NUVIO_MPV_HWDEC");
            if (!(env_hwdec && env_hwdec[0])) {
                api.set_property_string(p->mpv, "hwdec", "auto");
                p->hwdec_managed = 1;
            }
            fprintf(stderr, "nuvio-linux-bridge: using OpenGL rendering backend%s\n",
                    p->gl.drm_params.render_fd >= 0 ? " (DRM render node for hwdec interop)" : "");
            return;
        }
    }

    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    if (mpv_render_context_create_checked(p, params) == 0) {
        p->backend = BACKEND_SW;
        fprintf(stderr, "nuvio-linux-bridge: using software rendering backend\n");
    } else {
        p->backend = BACKEND_SW; // keep render() returning -1 via NULL rctx
        fprintf(stderr, "nuvio-linux-bridge: render context creation failed\n");
    }
}

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
    const char *perf = getenv("NUVIO_LINUX_PERF");
    p->perf_enabled = perf && strcmp(perf, "1") == 0;
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
    if (getenv("NUVIO_MPV_VERBOSE")) {
        api.set_option_string(p->mpv, "terminal", "yes");
        api.set_option_string(p->mpv, "msg-level", "all=v");
    } else {
        api.set_option_string(p->mpv, "terminal", "no");
    }
    // Diagnostics: NUVIO_MPV_AO=null isolates video timing from the audio
    // clock when chasing A-V sync problems.
    const char *env_ao = getenv("NUVIO_MPV_AO");
    if (env_ao && env_ao[0]) api.set_option_string(p->mpv, "ao", env_ao);
    // Network resilience for streaming sources
    api.set_option_string(p->mpv, "cache", "yes");
    api.set_option_string(p->mpv, "demuxer-max-bytes", "128MiB");
    api.set_option_string(p->mpv, "demuxer-max-back-bytes", "64MiB");

    // NUVIO_MPV_HWDEC overrides the caller's hwdec choice — the escape hatch
    // for setups where a specific decode path misbehaves (analogous to
    // NUVIO_LINUX_RENDER=sw for the render backend).
    const char *env_hwdec = getenv("NUVIO_MPV_HWDEC");
    const char *hwdec = (*env)->GetStringUTFChars(env, jhwdec, NULL);
    if (env_hwdec && env_hwdec[0]) {
        api.set_option_string(p->mpv, "hwdec", env_hwdec);
    } else {
        api.set_option_string(p->mpv, "hwdec", hwdec && hwdec[0] ? hwdec : "auto-copy");
    }
    (*env)->ReleaseStringUTFChars(env, jhwdec, hwdec);

    // HTTP headers arrive as "Key: Value" lines; mpv wants a comma-separated
    // list option, so commas and backslashes inside values must be escaped
    // (matches the macOS/Windows bridges, upstream PR #144). User-Agent gets
    // its own option when present.
    jsize headerCount = jheaderLines ? (*env)->GetArrayLength(env, jheaderLines) : 0;
    if (headerCount > 0) {
        size_t cap = 4096;
        size_t len = 0;
        char *joined = calloc(1, cap);
        for (jsize i = 0; i < headerCount; i++) {
            jstring jline = (jstring)(*env)->GetObjectArrayElement(env, jheaderLines, i);
            const char *line = (*env)->GetStringUTFChars(env, jline, NULL);
            if (strncasecmp(line, "user-agent:", 11) == 0) {
                const char *ua = line + 11;
                while (*ua == ' ') ua++;
                api.set_option_string(p->mpv, "user-agent", ua);
            } else {
                size_t need = len + strlen(line) * 2 + 2;
                if (need > cap) {
                    cap = need * 2;
                    joined = realloc(joined, cap);
                }
                if (len > 0) joined[len++] = ',';
                for (const char *c = line; *c; c++) {
                    if (*c == '\\' || *c == ',') joined[len++] = '\\';
                    joined[len++] = *c;
                }
                joined[len] = '\0';
            }
            (*env)->ReleaseStringUTFChars(env, jline, line);
            (*env)->DeleteLocalRef(env, jline);
        }
        if (len > 0) api.set_option_string(p->mpv, "http-header-fields", joined);
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

    // The render context is created lazily by the first render() call: the
    // OpenGL context must be current on the thread that creates and uses it,
    // and only the frame thread qualifies.

    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    p->pending_url = strdup(url);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
    return (jlong)(intptr_t)p;
}

BRIDGE_FN(void, dispose)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return;
    GlBackend *gl = &p->gl;
    if (p->backend == BACKEND_GL && gl->ctx) {
        // The frame thread has stopped by the time dispose runs, so it is
        // legal to make the context current here for teardown.
        gl->MakeCurrent(gl->dpy, gl->surf, gl->surf, gl->ctx);
    }
    if (p->rctx) api.render_context_free(p->rctx);
    if (p->backend == BACKEND_GL && gl->ctx) {
        if (gl->fbo) gl->DeleteFramebuffers(1, &gl->fbo);
        if (gl->tex) gl->DeleteTextures(1, &gl->tex);
        if (gl->pbo[0]) gl->DeleteBuffers(2, gl->pbo);
        gl->MakeCurrent(gl->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, NULL);
        gl->DestroyContext(gl->dpy, gl->ctx);
        if (gl->surf) gl->DestroySurface(gl->dpy, gl->surf);
    }
    // Closed after the render context (mpv's VA display references it).
    if (gl->drm_params.render_fd >= 0) close(gl->drm_params.render_fd);
    if (p->mpv) api.terminate_destroy(p->mpv);
    free(p->pending_url);
    pthread_mutex_destroy(&p->state_mutex);
    free(p);
}

// Renders the newest video frame into the caller's pixel memory (RGBA,
// stride=w*4; on the Kotlin side this is the Skia bitmap's own storage).
// Returns 1 if a new frame was written, 0 if nothing changed, -1 on error.
// Must always be called from the same (frame) thread.
BRIDGE_FN(jint, render)(JNIEnv *env, jclass cls, jlong handle, jlong pixelsAddr, jint w, jint h) {
    Player *p = player_from_handle(handle);
    if (!p) return -1;
    if (p->backend == BACKEND_PENDING) init_render_backend(p);
    if (!p->rctx) return -1;
    if (p->pending_url) {
        const char *cmd[] = {"loadfile", p->pending_url, NULL};
        api.command(p->mpv, cmd);
        free(p->pending_url);
        p->pending_url = NULL;
    }
    drain_events(p);

    uint64_t flags = api.render_context_update(p->rctx);
    int has_new_frame = (flags & MPV_RENDER_UPDATE_FRAME) != 0;
    if (!has_new_frame && p->backend != BACKEND_GL) return 0;

    void *pixels = (void *)(intptr_t)pixelsAddr;
    if (!pixels) return -1;

    if (p->backend == BACKEND_GL) {
        GlBackend *gl = &p->gl;
        if (!gl->MakeCurrent(gl->dpy, gl->surf, gl->surf, gl->ctx)) return -1;

        // Readback is pipelined through two PBOs: frame N is submitted as an
        // asynchronous pack transfer and delivered on the next call, while
        // frame N+1 renders. A blocking glReadPixels into client memory
        // stalled 50-230ms per frame on Iris Xe; one frame of extra latency
        // is invisible during video playback.
        if (!has_new_frame) {
            // No new frame (paused, EOF, or transfer still in flight): deliver
            // a finished transfer so frames are never stuck one call behind.
            // The older submission is gl->pbo_index (the next submit target);
            // deliver it before the newer one to keep frames in order.
            if (gl->pbo[0] && gl->pbo_w == w && gl->pbo_h == h) {
                int older = gl->pbo_index;
                if (gl_pbo_ready(gl, older)) return gl_deliver_pbo(gl, older, pixels);
                if (gl_pbo_ready(gl, older ^ 1)) return gl_deliver_pbo(gl, older ^ 1, pixels);
            }
            return 0;
        }

        if (!gl_ensure_fbo(gl, w, h)) return -1;
        if (!gl_ensure_pbos(gl, w, h)) return -1;
        mpv_opengl_fbo fbo = { .fbo = (int)gl->fbo, .w = w, .h = h, .internal_format = 0 };
        // glReadPixels returns rows bottom-up; mpv's default FBO orientation
        // already compensates for that, so no flip (verified with a
        // white-top/black-bottom test video against the software renderer).
        int flip_y = 0;
        // Never let render() sleep until the frame's target display time
        // (the default): our loop is update-callback-driven, not
        // vsync-driven, and the wait shows up as multi-hundred-ms render
        // stalls whenever mpv's frame scheduling is in flux.
        int block = 0;
        mpv_render_param rparams[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
            {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        int need_render_time = p->perf_enabled || p->hwdec_managed;
        int64_t t0 = need_render_time ? now_ns() : 0;
        if (api.render_context_render(p->rctx, rparams) < 0) return -1;
        int64_t t1 = need_render_time ? now_ns() : 0;

        if (p->hwdec_managed) {
            p->hwdec_watch_frames += 1;
            if (p->hwdec_watch_frames > HWDEC_WATCH_SKIP) {
                p->hwdec_watch_ns += t1 - t0;
                int judged = p->hwdec_watch_frames - HWDEC_WATCH_SKIP;
                if (judged >= HWDEC_WATCH_WINDOW ||
                    (judged >= HWDEC_WATCH_MIN_FRAMES && p->hwdec_watch_ns >= HWDEC_WATCH_MAX_TOTAL_NS)) {
                    p->hwdec_managed = 0; // judge exactly once
                    if (p->hwdec_watch_ns / judged > HWDEC_WATCH_AVG_LIMIT_NS) {
                        api.set_property_string(p->mpv, "hwdec", "auto-copy");
                        fprintf(stderr,
                                "nuvio-linux-bridge: hwdec interop renders at %.0fms/frame,"
                                " downgrading to auto-copy\n",
                                p->hwdec_watch_ns / 1e6 / judged);
                    }
                }
            }
        }

        // Deliver first, then read back into a *free* PBO. Submitting over a
        // still-pending PBO would throw away frames the GPU is about to
        // finish; under sustained GPU load that starved delivery completely
        // (both PBOs perpetually pending, none ever signaled in time).
        // gl->pbo_index is the older submission — deliver in order.
        int cur = gl->pbo_index;
        int prev = cur ^ 1;
        int delivered = 0;
        if (gl_pbo_ready(gl, cur)) delivered = gl_deliver_pbo(gl, cur, pixels);
        else if (gl_pbo_ready(gl, prev)) delivered = gl_deliver_pbo(gl, prev, pixels);

        int target = !gl->pbo_pending[cur] ? cur : (!gl->pbo_pending[prev] ? prev : -1);
        if (target >= 0) {
            gl->BindFramebuffer(GL_FRAMEBUFFER, gl->fbo);
            gl->BindBuffer(GL_PIXEL_PACK_BUFFER, gl->pbo[target]);
            gl->PixelStorei(GL_PACK_ALIGNMENT, 4);
            // GL_RGBA matches the GL_RGBA8 FBO; a format mismatch (BGRA)
            // kicks Mesa off the blit fastpath into per-pixel CPU conversion.
            gl->ReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, (void *)0);
            gl->BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            gl->pbo_fence[target] = gl->FenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            // Without a flush the batch (and the fence) can sit unsubmitted
            // in the command buffer indefinitely and ClientWaitSync(0)
            // never reports completion.
            gl->Flush();
            gl->pbo_pending[target] = 1;
            gl->pbo_index = target ^ 1;
        }
        // If both PBOs are still in flight this frame is rendered but not
        // read back — dropped for display, which is the honest option.

        if (p->perf_enabled) {
            int64_t t2 = now_ns();
            if (p->perf_window_start == 0) p->perf_window_start = t0;
            p->perf_render_ns += t1 - t0;
            p->perf_read_ns += t2 - t1;
            p->perf_frames += 1;
            if (t2 - p->perf_window_start >= 2000000000LL) {
                fprintf(stderr,
                        "nuvio-linux-bridge-perf: mpv_render_avg=%.2fms readback_avg=%.2fms frames=%d"
                        " pending=%d/%d wait=0x%x delivered_last=%d\n",
                        p->perf_render_ns / 1e6 / p->perf_frames,
                        p->perf_read_ns / 1e6 / p->perf_frames,
                        p->perf_frames,
                        gl->pbo_pending[0], gl->pbo_pending[1],
                        gl->last_wait_status, delivered);
                p->perf_window_start = t2;
                p->perf_render_ns = 0;
                p->perf_read_ns = 0;
                p->perf_frames = 0;
            }
        }
        return delivered;
    }

    int size[2] = {w, h};
    size_t stride = (size_t)w * 4;
    mpv_render_param rparams[] = {
        {MPV_RENDER_PARAM_SW_SIZE, size},
        {MPV_RENDER_PARAM_SW_FORMAT, "rgb0"}, // matches Skia RGBA_8888 + OPAQUE, same as the GL path
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

// Display dimensions of the current video (dwidth/dheight: storage size with
// the container aspect applied — the right basis for sizing the render
// target). 0 while no video is loaded.
BRIDGE_FN(jint, videoWidth)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return 0;
    int64_t v = 0;
    if (api.get_property(p->mpv, "dwidth", MPV_FORMAT_INT64, &v) < 0) return 0;
    return (jint)v;
}

BRIDGE_FN(jint, videoHeight)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return 0;
    int64_t v = 0;
    if (api.get_property(p->mpv, "dheight", MPV_FORMAT_INT64, &v) < 0) return 0;
    return (jint)v;
}

BRIDGE_FN(jlong, frameDropCount)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    if (!p) return 0;
    int64_t v = 0;
    if (api.get_property(p->mpv, "frame-drop-count", MPV_FORMAT_INT64, &v) < 0) return 0;
    return (jlong)v;
}

BRIDGE_FN(jdouble, estimatedVfFps)(JNIEnv *env, jclass cls, jlong handle) {
    Player *p = player_from_handle(handle);
    return p ? get_double(p, "estimated-vf-fps", 0.0) : 0.0;
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
