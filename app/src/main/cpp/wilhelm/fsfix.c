/* fsfix.c — LD_PRELOAD shim: fix ~4-minute black-screen launch delay on Android.
 *
 * Root cause:
 *   Wine probes the save-directory filesystem with ioctl(fd, FS_IOC_GETFLAGS, ...) before
 *   setting up inotify.  On Android FUSE storage the ioctl is denied by SELinux with EPERM.
 *   Wine treats EPERM as a recoverable permissions error and retries every ~150 ms for up to
 *   4 minutes before giving up.  The semantically correct response is ENOTTY ("operation not
 *   supported by this filesystem"), which causes Wine to skip inotify immediately.
 *
 * Fix:
 *   Intercept all ioctl(fd, FS_IOC_GETFLAGS, ...) calls and return -1 / ENOTTY.
 *   Every other ioctl is forwarded to the real implementation unchanged.
 *
 * Build:
 *   Compiled as libfsfix.so via extras/CMakeLists.txt (Android NDK, arm64-v8a + armeabi-v7a).
 *   Loaded via LD_PRELOAD from the APK native-library directory by BionicProgramLauncherComponent.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <linux/fs.h>

#ifndef FS_IOC_GETFLAGS
/* _IOR('f', 1, long): sizeof(long)==8 on aarch64 and x86-64 → 0x80086601 */
#define FS_IOC_GETFLAGS 0x80086601UL
#endif

typedef int (*ioctl_f)(int, int, ...);
static ioctl_f real_ioctl;

__attribute__((visibility("default")))
int ioctl(int fd, int req, ...)
{
    if ((unsigned long)req == (unsigned long)FS_IOC_GETFLAGS) {
        errno = ENOTTY;
        return -1;
    }
    if (!real_ioctl)
        real_ioctl = (ioctl_f)dlsym(RTLD_NEXT, "ioctl");
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return real_ioctl(fd, req, arg);
}
