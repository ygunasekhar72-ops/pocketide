/*
 * PocketIDE native PTY library - P3a proof of concept.
 *
 * Scope (M1-P3a only): one JNI PTY session backend behind PtyBridge:
 * openpty/fork/exec of /system/bin/sh (no /bin/sh), one reaper thread per
 * child (the only child owner), a poll/wake-pipe reader path, and exactly-
 * once bounded destroy. No terminal parser, renderer, keyboard UI, Linux
 * userspace, or Termux integration lives here.
 *
 * Concurrency invariants (documented, enforced by construction):
 *  - The reaper thread of a slot is the ONLY code path that calls waitpid()
 *    for that child: one reaping owner per child. (Rollback paths before
 *    registration reap inline while the slot is not published to callers.)
 *  - Reader entry into nativeRead is race-free: entry is denied once
 *    destroyStarted is set (checked under the slot mutex); the wake pipe
 *    wakes a parked poll. The master fd is never closed while readers>0.
 *  - nativeDestroy is exactly-once per live handle (engine contract), and
 *    is additionally safe when invoked concurrently or with a stale handle:
 *    the generation in the handle id and the slot state word reject both.
 *  - Raw wait status is retained internally (rawStatus); nativeAwaitExit
 *    exposes the mapping: WIFEXITED -> WEXITSTATUS, WIFSIGNALED ->
 *    128 + WTERMSIG, anything else -> -1.
 *  - argv/envp are fully materialized in malloc'd memory BEFORE fork(); the
 *    post-fork child path uses only async-safe calls (no JNI, no malloc,
 *    no locks) on the way to execve("/system/bin/sh"...).
 *  - Startup-error handling: the child reports failures down an
 *    O_CLOEXEC|O_NONBLOCK pipe as a 4-byte host-endian errno. The parent
 *    reads to EOF under a 2 s deadline: empty->success; 4 bytes->failure
 *    mapped to errno text; anything else->malformed startup. Every partial
 *    startup resource (master, slave, error pipe, un-reaped child) is
 *    closed/reaped on every failure path - rollback invariant I6.
 *  - Shutdown: destroy wakes the reader, waits for readers==0 within the
 *    4 s budget, then escalates SIGHUP -> 1 s grace -> SIGKILL -> 2 s grace,
 *    joins the reaper, closes all fds, and frees the slot. If the child is
 *    un-killable after the full budget (e.g. uninterruptible kernel wait),
 *    the slot is intentionally parked as RECLAIMING with its reaper still
 *    in waitpid: a documented bounded-leak fallback, not silent success.
 *  - Concurrent writes are serialized by a dedicated write mutex; the
 *    partial-write contract is "return delivered bytes; the Kotlin layer
 *    drops the remainder" (matches the engine's late-write semantics).
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <pty.h>

#define PT_MAX_SESSIONS 64
#define PT_MAX_ENV 128
#define PT_WRITE_CHUNK_MAX (64 * 1024)
#define PT_POLL_TICK_MS 250
#define PT_ERRPIPE_TIMEOUT_MS 2000
#define PT_DESTROY_NET_BUDGET_MS 4000
#define PT_SIGHUP_GRACE_MS 1000
#define PT_SIGKILL_GRACE_MS 2000

#define PT_MAX_ROWS 2048
#define PT_MAX_COLS 2048

static const char *POCKETPTY_VERSION = "pocketpty 1.0.0-p3";

typedef enum {
    PT_SLOT_FREE = 0,
    PT_SLOT_ACTIVE = 1,
    PT_SLOT_RECLAIMING = 2
} pt_state_t;

typedef struct {
    /* Guards every field of this slot. Signals use cv under mu. */
    pthread_mutex_t mu;
    pthread_cond_t cv;
    /* Serializes write loops (one nativeWrite holds it for its duration). */
    pthread_mutex_t wmu;

    pt_state_t state;
    long long generation;
    long long pid;          /* child pid == session leader (setsid). */
    int master;             /* pty master fd; closed last during destroy. */
    int wakeR;              /* reader shutdown pipe, O_CLOEXEC|O_NONBLOCK. */
    int wakeW;

    int readers;            /* # of nativeRead calls in flight. */
    int destroyStarted;     /* destroy claimed this slot once, exactly. */
    int finishing;          /* the final cleanup phase was claimed. */
    int sawEof;             /* a nativeRead observed EOF/EIO on master. */
    int reaperDone;         /* reaper recorded rawStatus. */
    int reaperLive;         /* reaper thread created successfully. */
    int rawStatus;          /* raw waitpid status - one reaping owner only. */

    pthread_t reaper;
} pt_slot_t;

static pt_slot_t g_slots[PT_MAX_SESSIONS];
static pthread_mutex_t g_reg_mu = PTHREAD_MUTEX_INITIALIZER;

/*
 * Child pid of the last SUCCESSFULLY registered nativeOpen. Debug-only
 * diagnostics accessor (POCKETPTY_DEBUG_DIAG builds) lets harnesses locate
 * the spawned session leader under /proc/<pid> (same-uid children are
 * inspectable by the parent app without any extra permission).
 */
static volatile pid_t g_last_child_pid = -1;

static long long pt_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Absolute deadline for pthread_cond_timedwait (default REALTIME clock). */
static void pt_deadline(struct timespec *out, long long delta_ms)
{
    long long ms = pt_now_ms() + delta_ms;
    out->tv_sec = (time_t)(ms / 1000);
    out->tv_nsec = (long)(ms % 1000) * 1000000L;
}

static void jni_throw_msg(JNIEnv *env, const char *cls, const char *msg)
{
    jclass c = (*env)->FindClass(env, cls);
    if (c != NULL) (*env)->ThrowNew(env, c, msg);
}

static void jni_throw_errno(JNIEnv *env, const char *cls, const char *prefix, int err)
{
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: %s", prefix, strerror(err));
    jni_throw_msg(env, cls, buf);
}

/* --- handle map ------------------------------------------------------- */

static void pt_slot_init(pt_slot_t *s)
{
    pthread_mutex_init(&s->mu, NULL);
    pthread_cond_init(&s->cv, NULL);
    pthread_mutex_init(&s->wmu, NULL);
    s->state = PT_SLOT_FREE;
    s->generation = 0;
    s->pid = -1;
    s->master = -1;
    s->wakeR = -1;
    s->wakeW = -1;
    s->readers = 0;
    s->destroyStarted = 0;
    s->finishing = 0;
    s->sawEof = 0;
    s->reaperDone = 0;
    s->reaperLive = 0;
    s->rawStatus = 0;
}

static void pt_init_registry(void)
{
    static int inited = 0;
    if (inited) return;
    pthread_mutex_lock(&g_reg_mu);
    if (!inited) {
        for (int i = 0; i < PT_MAX_SESSIONS; ++i) pt_slot_init(&g_slots[i]);
        inited = 1;
    }
    pthread_mutex_unlock(&g_reg_mu);
}

/**
 * Locks slot->mu when returning non-NULL; caller must unlock.
 * Returns NULL (and touches nothing) for foreign/stale/free handles.
 */
static pt_slot_t *pt_lookup_locked(long long handle)
{
    int idx = (int)(handle >> 32);
    long long gen = (long long)(int32_t)(handle & 0xffffffffLL);
    if (idx < 0 || idx >= PT_MAX_SESSIONS) return NULL;
    pt_slot_t *s = &g_slots[idx];
    pthread_mutex_lock(&s->mu);
    if (s->state == PT_SLOT_FREE || s->generation != gen) {
        pthread_mutex_unlock(&s->mu);
        return NULL;
    }
    return s;
}

static void pt_drain_wake(int wakeR)
{
    char b[64];
    for (;;) {
        ssize_t n = read(wakeR, b, sizeof(b));
        if (n > 0) continue;
        if (n < 0 && errno == EINTR) continue;
        break; /* EAGAIN or EOF */
    }
}

/*
 * pipe2(O_CLOEXEC|O_NONBLOCK) is not declared on bionic without feature
 * test macros AGP does not set; pipe() + fcntl is equivalent and portable.
 */
static int pt_pipe_cloexec_nonblock(int fds[2])
{
    if (pipe(fds) != 0) return -1;
    for (int i = 0; i < 2; ++i) {
        fcntl(fds[i], F_SETFL, fcntl(fds[i], F_GETFL) | O_NONBLOCK);
        fcntl(fds[i], F_SETFD, fcntl(fds[i], F_GETFD) | FD_CLOEXEC);
    }
    return 0;
}

/* The single reaping owner for the child of this slot. */
static void *pt_reaper_fn(void *arg)
{
    pt_slot_t *s = (pt_slot_t *)arg;
    int st = 0;
    for (;;) {
        pid_t r = waitpid((pid_t)s->pid, &st, 0);
        if (r == (pid_t)s->pid) break;
        if (r < 0 && errno == EINTR) continue;
        st = 0; /* no child to wait for: treat as exited w/ status 0 */
        break;
    }
    pthread_mutex_lock(&s->mu);
    s->rawStatus = st;
    s->reaperDone = 1;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    return NULL;
}

/*
 * Final destroy phase: exactly-once per slot, called with slot UNLOCKED.
 * Assumes the caller holds the destroyStarted claim and (for the direct
 * destroy path) readers == 0. Returns 0 when the slot was released, -1
 * when the bounded budget expired and the slot is parked RECLAIMING.
 */
static int pt_finish_destroy(pt_slot_t *s)
{
    pthread_mutex_lock(&s->mu);
    if (s->finishing) {           /* another claimant in progress */
        pthread_mutex_unlock(&s->mu);
        return 0;
    }
    s->finishing = 1;
    pthread_mutex_unlock(&s->mu);

    long long pid = s->pid;
    int done = 0;

    if (!s->sawEof) {
        /* Session leader with a controlling pty dies on default-term HUP. */
        kill((pid_t)pid, SIGHUP);
    }
    struct timespec dl;
    pt_deadline(&dl, PT_SIGHUP_GRACE_MS);
    pthread_mutex_lock(&s->mu);
    while (!s->reaperDone) {
        if (pthread_cond_timedwait(&s->cv, &s->mu, &dl) == ETIMEDOUT) break;
    }
    done = s->reaperDone;
    pthread_mutex_unlock(&s->mu);

    if (!done) {
        kill((pid_t)pid, SIGKILL);
        pt_deadline(&dl, PT_SIGKILL_GRACE_MS);
        pthread_mutex_lock(&s->mu);
        while (!s->reaperDone) {
            if (pthread_cond_timedwait(&s->cv, &s->mu, &dl) == ETIMEDOUT) break;
        }
        done = s->reaperDone;
        pthread_mutex_unlock(&s->mu);
    }

    if (!done) {
        /* Documented bounded leak: un-killable child (kernel-internal wait).
         * Slot stays RECLAIMING forever; the reaper thread stays parked. */
        pthread_mutex_lock(&s->mu);
        s->state = PT_SLOT_RECLAIMING;
        pthread_cond_broadcast(&s->cv);
        pthread_mutex_unlock(&s->mu);
        return -1;
    }

    if (s->reaperLive) {
        pthread_join(s->reaper, NULL);
        s->reaperLive = 0;
    }
    pthread_mutex_lock(&s->mu);
    if (s->master >= 0) { close(s->master); s->master = -1; }
    if (s->wakeR >= 0) { close(s->wakeR); s->wakeR = -1; }
    if (s->wakeW >= 0) { close(s->wakeW); s->wakeW = -1; }
    s->state = PT_SLOT_FREE;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    return 0;
}

/**
 * Reader-exit helper: when the last in-flight reader returns while destroy
 * was already claimed, the reader performs the deferred final cleanup.
 */
static void pt_reader_exit_cleanup(pt_slot_t *s)
{
    pthread_mutex_lock(&s->mu);
    int mine = (s->destroyStarted && s->readers == 0);
    pthread_mutex_unlock(&s->mu);
    if (mine) pt_finish_destroy(s);
}

/* --- JNI -------------------------------------------------------------- */

JNIEXPORT jstring JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeVersion(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    return (*env)->NewStringUTF(env, POCKETPTY_VERSION);
}

/* Child failure reporter: nonblocking 4-byte errno, then _exit(127). */
static void pt_child_fail(int w, int err)
{
    int32_t code = (int32_t)err;
    const char *b = (const char *)&code;
    size_t left = sizeof(code);
    while (left > 0) {
        ssize_t n = write(w, b, left);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        b += n;
        left -= (size_t)n;
    }
    _exit(127);
}

/*
 * Shared open implementation. Throws PtyStartException-style IOException on
 * failure in production mode (diag == 0, returning -1). In debug-diag mode
 * it appends a phase trace into [trace] (fixed-size buffer) and returns
 * without throwing so the harness can localize a wedged phase.
 */
static void pt_trace(char *trace, size_t traceSz, long long t0, const char *fmt, ...)
{
    if (trace == NULL) return;
    va_list ap;
    int used = (int)strlen(trace);
    va_start(ap, fmt);
    if (used < (int)traceSz) {
        int n = vsnprintf(trace + used, traceSz - (size_t)used, fmt, ap);
        if (n < 0) n = 0;
        used += n;
        if (used < (int)traceSz - 2) {
            trace[used++] = ' ';
            trace[used] = '\0';
        }
    }
    va_end(ap);
    (void)t0;
}

static long long pt_open_internal(
    JNIEnv *env, jstring jcwd, jobjectArray jenv,
    int diag, char *trace, size_t traceSz
)
{
    pt_init_registry();
    long long t0 = pt_now_ms();

    /* 1. Materialize argv/envp BEFORE fork. */
    const char *cwdUtf = (*env)->GetStringUTFChars(env, jcwd, NULL);
    if (!cwdUtf) {
        jni_throw_msg(env, "java/io/IOException", "pocketpty: GetStringUTFChars failed");
        return -1;
    }
    char *cwd = strdup(cwdUtf);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwdUtf);
    if (!cwd) {
        pt_trace(trace, traceSz, t0, "oom-cwd");
        if (!diag) jni_throw_msg(env, "java/io/IOException", "pocketpty: out of memory");
        return -1;
    }

    jsize n = (*env)->GetArrayLength(env, jenv);
    if (n < 0 || n > PT_MAX_ENV) {
        free(cwd);
        jni_throw_msg(env, "java/lang/IllegalArgumentException", "pocketpty: env array too large");
        return -1;
    }
    char **envp = (char **)malloc(sizeof(char *) * (size_t)(n + 1));
    if (!envp) {
        free(cwd);
        jni_throw_msg(env, "java/io/IOException", "pocketpty: out of memory");
        return -1;
    }
    for (int i = 0; i < n; ++i) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jenv, i);
        if (!js) {
            for (int k = 0; k < i; ++k) free(envp[k]);
            free(envp);
            free(cwd);
            jni_throw_msg(env, "java/lang/IllegalArgumentException", "pocketpty: null env entry");
            return -1;
        }
        const char *u = (*env)->GetStringUTFChars(env, js, NULL);
        envp[i] = u ? strdup(u) : NULL;
        (*env)->ReleaseStringUTFChars(env, js, u);
        (*env)->DeleteLocalRef(env, js);
        if (!envp[i]) {
            for (int k = 0; k < i; ++k) free(envp[k]);
            free(envp);
            free(cwd);
            jni_throw_msg(env, "java/io/IOException", "pocketpty: out of memory");
            return -1;
        }
    }
    envp[n] = NULL;

    /* 2. PTY + startup-error pipe. */
    int master = -1, slave = -1;
    int errPipe[2] = { -1, -1 };
    pid_t pid = -1;
    long long ret = -1;

    if (openpty(&master, &slave, NULL, NULL, NULL) != 0) {
        pt_trace(trace, traceSz, t0, "openpty-fail e=%d", errno);
        if (!diag) jni_throw_errno(env, "java/io/IOException", "pocketpty: openpty failed", errno);
        goto out;
    }
    pt_trace(trace, traceSz, t0, "openpty-ok m=%d s=%d", master, slave);
    if (pt_pipe_cloexec_nonblock(errPipe) != 0) {
        pt_trace(trace, traceSz, t0, "errpipe-fail e=%d", errno);
        if (!diag) jni_throw_errno(env, "java/io/IOException", "pocketpty: pipe failed", errno);
        goto out;
    }

    pid = fork();
    if (pid < 0) {
        pt_trace(trace, traceSz, t0, "fork-fail e=%d", errno);
        if (!diag) jni_throw_errno(env, "java/io/IOException", "pocketpty: fork failed", errno);
        goto out;
    }
    pt_trace(trace, traceSz, t0, "fork-ok pid=%d", (int)pid);

    if (pid == 0) {
        /* Child: no JNI, no malloc, no locks below this line. */
        close(master);
        close(errPipe[0]);
        if (setsid() < 0) pt_child_fail(errPipe[1], errno);
        /* Best effort: acquire controlling pty so HUP/EOF semantics hold. */
        (void)ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        if (chdir(cwd) < 0) pt_child_fail(errPipe[1], errno);
        {
            char *argv[] = { "sh", NULL };
            execve("/system/bin/sh", argv, envp);
        }
        pt_child_fail(errPipe[1], errno);
        _exit(127); /* not reached */
    }

    /* 3. Parent: probe the startup-error pipe to EOF under a deadline. */
    close(slave);
    slave = -1;
    close(errPipe[1]);
    errPipe[1] = -1;

    {
        char tmp[16];
        int got = 0;
        int eofSeen = 0;
        long long probeStart = pt_now_ms();
        long long dlMs = probeStart + PT_ERRPIPE_TIMEOUT_MS;
        while (!eofSeen) {
            struct pollfd pf = { errPipe[0], POLLIN, 0 };
            long long left = dlMs - pt_now_ms();
            if (left <= 0) break;
            int pr = poll(&pf, 1, (int)left);
            if (pr < 0 && errno == EINTR) continue;
            if (pr <= 0) break; /* timeout (or poll error): cannot confirm */
            if (!(pf.revents & (POLLIN | POLLHUP | POLLERR))) break;
            for (;;) {
                ssize_t r = read(errPipe[0], tmp + got, sizeof(tmp) - (size_t)got);
                if (r > 0) {
                    got += (int)r;
                    if (got > 4) break; /* malformed report; still fail below */
                } else if (r == 0) {
                    eofSeen = 1;
                    break;
                } else if (errno == EINTR) {
                    continue;
                } else {
                    break; /* EAGAIN or unexpected: wait for next tick */
                }
            }
            if (got > 4) break;
        }
        pt_trace(trace, traceSz, t0, "errprobe eof=%d got=%d ms=%lld",
                 eofSeen, got, pt_now_ms() - probeStart);
        if (!eofSeen || got > 0) {
            /* Start failure (or unconfirmable): reap the child inline. */
            for (;;) {
                int st;
                pid_t r = waitpid(pid, &st, 0);
                if (r == pid) break;
                if (r < 0 && errno == EINTR) continue;
                break;
            }
            const char *what = "child setup failed";
            char buf[128];
            if (got == 4) {
                int childErrno;
                memcpy(&childErrno, tmp, 4);
                snprintf(buf, sizeof(buf), "%s", strerror(childErrno));
                what = buf;
            }
            pt_trace(trace, traceSz, t0, "child-failed claim=%s", what);
            if (!diag) jni_throw_msg(env, "java/io/IOException", what);
            goto out;
        }
        pt_trace(trace, traceSz, t0, "child-confirmed");
    }

    /* 4. Wake pipe + master nonblocking + slot registration + reaper. */
    close(errPipe[0]);
    errPipe[0] = -1;
    {
        int fl = fcntl(master, F_GETFL);
        if (fl >= 0) (void)fcntl(master, F_SETFL, fl | O_NONBLOCK);
    }
    pt_trace(trace, traceSz, t0, "master-nonblock");
    {
        int wake[2] = { -1, -1 };
        if (pt_pipe_cloexec_nonblock(wake) != 0) {
            pt_trace(trace, traceSz, t0, "wakepipe-fail e=%d", errno);
            jni_throw_errno(env, "java/io/IOException", "pocketpty: wake pipe failed", errno);
            /* reap + close (rollback I6) */
            for (;;) {
                int st;
                pid_t r = waitpid(pid, &st, 0);
                if (r == pid) break;
                if (r < 0 && errno == EINTR) continue;
                break;
            }
            close(master);
            goto out;
        }
        pthread_mutex_lock(&g_reg_mu);
        pt_slot_t *s = NULL;
        for (int i = 0; i < PT_MAX_SESSIONS; ++i) {
            if (g_slots[i].state == PT_SLOT_FREE) { s = &g_slots[i]; break; }
        }
        int idx = -1;
        if (s != NULL) {
            idx = (int)(s - g_slots);
            pthread_mutex_lock(&s->mu);
            s->generation += 1;
            s->pid = pid;
            s->master = master;
            s->wakeR = wake[0];
            s->wakeW = wake[1];
            s->readers = 0;
            s->destroyStarted = 0;
            s->finishing = 0;
            s->sawEof = 0;
            s->reaperDone = 0;
            s->reaperLive = 0;
            s->rawStatus = 0;
            s->state = PT_SLOT_ACTIVE;
            g_last_child_pid = (pid_t)pid;
            pthread_mutex_unlock(&s->mu);

            if (pthread_create(&s->reaper, NULL, pt_reaper_fn, s) == 0) {
                pthread_mutex_lock(&s->mu);
                s->reaperLive = 1;
                pthread_mutex_unlock(&s->mu);
                ret = ((long long)idx << 32) | s->generation;
                pt_trace(trace, traceSz, t0, "reaped-ok slot=%d ret=%lld total=%lldms",
                         idx, ret, pt_now_ms() - t0);
            } else {
                /* Rollback: no zombie, no leaked fds, slot back to FREE. */
                kill(pid, SIGKILL);
                for (;;) {
                    int st;
                    pid_t r = waitpid(pid, &st, 0);
                    if (r == pid) break;
                    if (r < 0 && errno == EINTR) continue;
                    break;
                }
                s->wakeR = -1; s->wakeW = -1;
                close(wake[0]);
                close(wake[1]);
                s->master = -1;
                close(master);
                s->state = PT_SLOT_FREE;
                jni_throw_msg(env, "java/io/IOException",
                              "pocketpty: cannot start reaper thread");
            }
        } else {
            kill(pid, SIGKILL);
            for (;;) {
                int st;
                pid_t r = waitpid(pid, &st, 0);
                if (r == pid) break;
                if (r < 0 && errno == EINTR) continue;
                break;
            }
            close(wake[0]);
            close(wake[1]);
            close(master);
            jni_throw_msg(env, "java/io/IOException", "pocketpty: session table full");
        }
        pthread_mutex_unlock(&g_reg_mu);
        if (ret >= 0) goto done; /* success: skip common cleanup */
        goto out;
    }

    return -1; /* unreachable */

out:
    if (master >= 0) close(master);
    if (slave >= 0) close(slave);
    if (errPipe[0] >= 0) close(errPipe[0]);
    if (errPipe[1] >= 0) close(errPipe[1]);
    free(cwd);
    for (int i = 0; i < n; ++i) free(envp[i]);
    free(envp);
    pt_trace(trace, traceSz, t0, "open-failed total=%lldms", pt_now_ms() - t0);
    return -1;

done:
    free(cwd);
    for (int i = 0; i < n; ++i) free(envp[i]);
    free(envp);
    return ret;
}

JNIEXPORT jlong JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeOpen(JNIEnv *env, jclass clazz, jstring jcwd, jobjectArray jenv)
{
    return pt_open_internal(env, jcwd, jenv, 0, NULL, 0);
}

#ifdef POCKETPTY_DEBUG_DIAG
/*
 * Debug-only phase-traced variant of nativeOpen: identical semantics, no
 * exceptions on failure (failure still returns -1), and the returned string
 * carries the per-phase trace. Never linked into release builds.
 */
JNIEXPORT jstring JNICALL
Java_com_pocketide_mobile_devicecheck_PtyDiagBridge_nativeOpenDiag(JNIEnv *env, jclass clazz, jstring jcwd, jobjectArray jenv)
{
    static const size_t TRACE_SZ = 2048;
    char trace[TRACE_SZ];
    trace[0] = '\0';
    long long h = pt_open_internal(env, jcwd, jenv, 1, trace, TRACE_SZ);
    char out[TRACE_SZ + 64];
    snprintf(out, sizeof(out), "H=%lld |%s", h, trace);
    return (*env)->NewStringUTF(env, out);
}
#endif

#ifdef POCKETPTY_DEBUG_DIAG
/*
 * Debug-only self-test of the raw ptmx->pts data path in THIS process:
 * open(/dev/ptmx) -> TIOCGPTN -> slave open(/dev/pts/N) -> write master,
 * poll+read slave (up to 1.5s) -> write slave, poll+read master. Exposes
 * whether the kernel actually delivers bytes between this app's ptmx and
 * its pts node (the ROOT-CAUSE discriminator for the silent-shell defect
 * observed on-device). No session state is touched.
 */
JNIEXPORT jstring JNICALL
Java_com_pocketide_mobile_devicecheck_PtyDiagBridge_nativePtmxSelfTest(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    char out[1024];
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        snprintf(out, sizeof(out), "ptmx-open-failed e=%d", errno);
        return (*env)->NewStringUTF(env, out);
    }
    int n = -1;
    if (ioctl(master, TIOCGPTN, &n) != 0 || n < 0) {
        snprintf(out, sizeof(out), "ptiogptn-failed e=%d", errno);
        close(master);
        return (*env)->NewStringUTF(env, out);
    }
    /* TIOCSPTLUN ("unlock") is not exposed by bionic headers; the kernel
     * convention is *arg == 0 (unlocked), any non-zero value locks the pts. */
#ifndef TIOCSPTLUN
#define TIOCSPTLUN _IOW('T', 0x31, int)
#endif
    int unlockVal = 0;
    if (ioctl(master, TIOCSPTLUN, &unlockVal) != 0) {
        snprintf(out, sizeof(out), "unlockpt-failed e=%d", errno);
        close(master);
        return (*env)->NewStringUTF(env, out);
    }
    char slavePath[64];
    snprintf(slavePath, sizeof(slavePath), "/dev/pts/%d", n);
    int slave = open(slavePath, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) {
        snprintf(out, sizeof(out), "slave-open-failed path=%s e=%d", slavePath, errno);
        close(master);
        return (*env)->NewStringUTF(env, out);
    }
    (void)fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);
    (void)fcntl(slave, F_SETFL, fcntl(slave, F_GETFL) | O_NONBLOCK);

    const char *payload = "SELFTEST-M2S-7717\n";
    size_t plen = strlen(payload);
    int wroteM = 0;
    struct pollfd wm = { master, POLLOUT, 0 };
    if (poll(&wm, 1, 1000) > 0)
        wroteM = (int)write(master, payload, plen);

    char gotS[128] = { 0 };
    int gotSlen = 0;
    {
        long long dl = pt_now_ms() + 1500;
        for (;;) {
            struct pollfd p = { slave, POLLIN, 0 };
            if (poll(&p, 1, 250) <= 0) { if (pt_now_ms() >= dl) break; continue; }
            ssize_t r = read(slave, gotS + gotSlen, sizeof(gotS) - 1 - gotSlen);
            if (r > 0) { gotSlen += (int)r; break; }
            if (errno == EAGAIN || errno == EINTR) continue;
            break;
        }
    }
    gotS[gotSlen > 0 ? (sizeof(gotS) - 1 < (size_t)gotSlen ? (int)sizeof(gotS) - 1 : gotSlen) : 0] = '\0';

    int wroteS = 0;
    struct pollfd ws = { slave, POLLOUT, 0 };
    if (poll(&ws, 1, 1000) > 0)
        wroteS = (int)write(slave, "S2M-4441\n", 9);

    char gotM[128] = { 0 };
    int gotMlen = 0;
    {
        long long dl = pt_now_ms() + 1500;
        for (;;) {
            struct pollfd p = { master, POLLIN, 0 };
            if (poll(&p, 1, 250) <= 0) { if (pt_now_ms() >= dl) break; continue; }
            ssize_t r = read(master, gotM + gotMlen, sizeof(gotM) - 1 - gotMlen);
            if (r > 0) { gotMlen += (int)r; break; }
            if (errno == EAGAIN || errno == EINTR) continue;
            break;
        }
    }
    gotM[gotMlen > 0 ? (sizeof(gotM) - 1 < (size_t)gotMlen ? (int)sizeof(gotM) - 1 : gotMlen) : 0] = '\0';

    char out2[1536];
    snprintf(out2, sizeof(out2),
             "ptn=%d slavePath=%s masterWrite=%d bytes=%d slaveRead=%d %s "
             "slaveWrite=%d masterRead=%d %s",
             n, slavePath, wroteM, (int)plen, gotSlen, gotS,
             wroteS, gotMlen, gotM);
    close(slave);
    close(master);

    /* (b) bionic openpty pair — same primitives the production session uses. */
    int bMaster = -1, bSlave = -1;
    int wroteB = 0, gotBSlen = 0;
    char bBuf[128] = { 0 };
    if (openpty(&bMaster, &bSlave, NULL, NULL, NULL) == 0) {
        (void)fcntl(bMaster, F_SETFL, fcntl(bMaster, F_GETFL) | O_NONBLOCK);
        (void)fcntl(bSlave, F_SETFL, fcntl(bSlave, F_GETFL) | O_NONBLOCK);
        struct pollfd wb = { bMaster, POLLOUT, 0 };
        if (poll(&wb, 1, 1000) > 0)
            wroteB = (int)write(bMaster, "BIONIC-PAIR-2211\n", 17);
        long long dl2 = pt_now_ms() + 1500;
        for (;;) {
            struct pollfd pb = { bSlave, POLLIN, 0 };
            if (poll(&pb, 1, 250) <= 0) { if (pt_now_ms() >= dl2) break; continue; }
            ssize_t r = read(bSlave, bBuf, sizeof(bBuf) - 1);
            if (r > 0) { gotBSlen = (int)r; break; }
            if (errno == EAGAIN || errno == EINTR) continue;
            break;
        }
        bBuf[gotBSlen >= 0 ? (sizeof(bBuf) - 1 < gotBSlen ? (int)sizeof(bBuf) - 1 : gotBSlen) : 0] = '\0';
        close(bSlave);
        close(bMaster);
    }

    char finalOut[2048];
    snprintf(finalOut, sizeof(finalOut), "manual=%s bionicPair=%s masterWrite=%d slaveRead=%d %s",
             out2, (bMaster < 0 ? "openpty-failed" : "ok"),
             wroteB, gotBSlen, bBuf);
    return (*env)->NewStringUTF(env, finalOut);
}

/*
 * Debug-only: pid of the child spawned by the most recent successful open.
 * -1 when no open has succeeded since process start (or a release build).
 * Read-only accessor; no locks (single word, best-effort diagnostics).
 */
JNIEXPORT jlong JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeLastChildPid(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong)g_last_child_pid;
}
#endif

/*
 * Reader hook. Returns: >0 bytes copied; 0 = EOF (child side is gone);
 * -1 = destroy claimed this slot (wakeup); -2 = stale/foreign handle;
 * -3 = unexpected I/O error (throws IOException).
 */
JNIEXPORT jint JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeRead(JNIEnv *env, jclass clazz, jlong handle, jbyteArray jbuf)
{
    (void)clazz;
    pt_slot_t *s = pt_lookup_locked(handle);
    if (!s) return -2;

    if (s->destroyStarted) { /* deny entry once destroy is claimed */
        pthread_mutex_unlock(&s->mu);
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, jbuf);
    if (len <= 0) {
        pthread_mutex_unlock(&s->mu);
        jni_throw_msg(env, "java/lang/IllegalArgumentException", "pocketpty: empty read buffer");
        return -2;
    }
    char *buf = (char *)malloc((size_t)len);
    if (!buf) {
        pthread_mutex_unlock(&s->mu);
        jni_throw_msg(env, "java/io/IOException", "pocketpty: out of memory");
        return -2;
    }

    s->readers++;
    jint result = 0;
    int done = 0;
    while (!done) {
        pthread_mutex_lock(&s->mu);
        int stop = s->destroyStarted || s->state != PT_SLOT_ACTIVE;
        int master = s->master;
        int wakeR = s->wakeR;
        pthread_mutex_unlock(&s->mu);
        if (stop || master < 0) { result = -1; done = 1; break; }

        struct pollfd fds[2] = { { master, POLLIN, 0 }, { wakeR, POLLIN, 0 } };
        int pr = poll(fds, 2, PT_POLL_TICK_MS);
        if (pr < 0 && errno == EINTR) continue;
        if (pr < 0) { result = -3; done = 1; break; }
        if (pr == 0) continue; /* idle tick: re-check destroyStarted */

        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            pt_drain_wake(wakeR);
            result = -1;
            done = 1;
            break;
        }
        if (fds[0].revents & (POLLIN | POLLERR | POLLHUP)) {
            ssize_t nr = read(master, buf, (size_t)len);
            if (nr > 0) {
                (*env)->SetByteArrayRegion(env, jbuf, 0, (jint)nr, (jbyte *)buf);
                result = (jint)nr;
                done = 1;
                break;
            }
            if (nr == 0) { result = 0; done = 1; break; }  /* EOF */
            if (errno == EINTR || errno == EAGAIN) continue;
            if (errno == EIO) {
                pthread_mutex_lock(&s->mu);
                s->sawEof = 1;
                pthread_mutex_unlock(&s->mu);
                result = 0; /* child side gone */
                done = 1;
                break;
            }
            result = -3; /* unexpected I/O error */
            done = 1;
            break;
        }
        /* spurious revents without readable master: loop */
    }

    pthread_mutex_lock(&s->mu);
    s->readers--;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);

    free(buf);
    if (result == -3) {
        pt_reader_exit_cleanup(s);
        jni_throw_msg(env, "java/io/IOException", "pocketpty: pty I/O error");
        return -3;
    }
    if (result == 0 || result == -1) pt_reader_exit_cleanup(s);
    return result;
}

/*
 * Stdin write. Returns the number of bytes delivered; the remainder is
 * dropped (documented engine-late-write contract). Partial delivery on
 * shutdown/child-gone is reported by returning the partial count.
 */
JNIEXPORT jint JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeWrite(JNIEnv *env, jclass clazz, jlong handle, jbyteArray jbuf)
{
    (void)clazz;
    jsize len = (*env)->GetArrayLength(env, jbuf);
    if (len <= 0) return 0;
    if (len > PT_WRITE_CHUNK_MAX) {
        jni_throw_msg(env, "java/lang/IllegalArgumentException", "pocketpty: write chunk too large");
        return 0;
    }
    char *buf = (char *)malloc((size_t)len);
    if (!buf) {
        jni_throw_msg(env, "java/io/IOException", "pocketpty: out of memory");
        return 0;
    }
    (*env)->GetByteArrayRegion(env, jbuf, 0, len, (jbyte *)buf);

    pt_slot_t *s = pt_lookup_locked(handle);
    if (!s) {
        free(buf);
        return 0; /* stale handle: late write, dropped silently */
    }
    pthread_mutex_unlock(&s->mu);

    pthread_mutex_lock(&s->wmu);
    jint delivered = 0;
    int stop;
    int master, wakeR;
    while (delivered < len) {
        pthread_mutex_lock(&s->mu);
        stop = s->destroyStarted || s->state != PT_SLOT_ACTIVE || s->master < 0;
        master = s->master;
        wakeR = s->wakeR;
        pthread_mutex_unlock(&s->mu);
        if (stop) break;

        struct pollfd fds[2] = { { master, POLLOUT, 0 }, { wakeR, POLLIN, 0 } };
        int pr = poll(fds, 2, PT_POLL_TICK_MS);
        if (pr < 0 && errno == EINTR) continue;
        if (pr < 0) break;
        if (pr == 0) continue;

        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            pt_drain_wake(wakeR);
            break; /* destroy wakeup: partial delivery stands */
        }
        if (fds[0].revents & (POLLOUT | POLLERR | POLLHUP)) {
            ssize_t nw = write(master, buf + delivered, (size_t)(len - delivered));
            if (nw > 0) { delivered += (jint)nw; continue; }
            if (nw < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            break; /* EIO or worse: partial delivery stands */
        }
    }
    pthread_mutex_unlock(&s->wmu);
    free(buf);
    return delivered;
}

/*
 * SIGHUP to the session leader. Repeat-tolerant per the engine contract;
 * no-op once the reaper recorded the child exit.
 */
JNIEXPORT jint JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeRequestExit(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    pt_slot_t *s = pt_lookup_locked(handle);
    if (!s) return -1;
    if (!s->reaperDone && !s->destroyStarted) {
        kill((pid_t)s->pid, SIGHUP);
    }
    pthread_mutex_unlock(&s->mu);
    return 0;
}

/*
 * Propagates a window resize to the pty (ioctl TIOCSWINSZ on the master).
 * The kernel delivers SIGWINCH to the session leader's foreground process
 * group automatically. Codes: 0 ok; -2 stale handle; -3 ioctl failure;
 * -4 rejected arguments (rows/cols outside [1, PT_MAX_ROWS//COLS]).
 * Called under the slot mutex: resize is a single ioctl, cheap.
 */
JNIEXPORT jint JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeResize(JNIEnv *env, jclass clazz,
                                                          jlong handle, jint rows, jint cols)
{
    (void)env; (void)clazz;
    pt_slot_t *s = pt_lookup_locked(handle);
    if (!s) return -2;
    int rc = 0;
    if (rows < 1 || rows > PT_MAX_ROWS || cols < 1 || cols > PT_MAX_COLS) {
        rc = -4;
    } else {
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short)rows;
        ws.ws_col = (unsigned short)cols;
        ws.ws_xpixel = 0;
        ws.ws_ypixel = 0;
        if (ioctl(s->master, TIOCSWINSZ, &ws) != 0) rc = -3;
    }
    pthread_mutex_unlock(&s->mu);
    return rc;
}

/* Blocks until the child is reaped; returns the exit-code mapping. */
JNIEXPORT jint JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeAwaitExit(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    pt_slot_t *s = pt_lookup_locked(handle);
    if (!s) return -1;
    while (!s->reaperDone) {
        pthread_cond_wait(&s->cv, &s->mu);
    }
    int st = s->rawStatus;
    pthread_mutex_unlock(&s->mu);
    if (WIFEXITED(st)) return WEXITSTATUS(st);
    if (WIFSIGNALED(st)) return 128 + WTERMSIG(st);
    return -1;
}

/*
 * Exactly-once destroy per live handle (engine contract), safe under
 * concurrency and stale handles. Sequence: wake readers; wait readers==0
 * within PT_DESTROY_NET_BUDGET_MS; SIGHUP/kill escalation with grace
 * periods; join reaper; close every fd; publish the slot as FREE.
 */
JNIEXPORT jint JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    pt_slot_t *s = pt_lookup_locked(handle);
    if (!s) return -2;
    if (s->destroyStarted || s->finishing) { /* concurrent destroy: tolerated */
        pthread_mutex_unlock(&s->mu);
        return 0;
    }
    s->destroyStarted = 1;

    if (s->wakeW >= 0) {
        char b = 'W';
        ssize_t nw = write(s->wakeW, &b, 1);
        (void)nw;
    }

    struct timespec dl;
    pt_deadline(&dl, PT_DESTROY_NET_BUDGET_MS);
    while (s->readers > 0) {
        if (pthread_cond_timedwait(&s->cv, &s->mu, &dl) == ETIMEDOUT) break;
    }
    if (s->readers > 0) {
        /* Reader must still exit (it saw destruction); deferred cleanup:
         * the LAST reader to exit performs the final phase. */
        pthread_mutex_unlock(&s->mu);
        return -1;
    }
    pthread_mutex_unlock(&s->mu);
    return pt_finish_destroy(s) == 0 ? 0 : -1;
}
