package dev.verity.core.display;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import dev.verity.core.install.InstallContext;
import dev.verity.core.profile.Profile;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Runs each client on its own invisible Win32 desktop: the game renders normally but never appears on screen
 * or steals focus. Because GPU drivers may refuse to accelerate windows on a desktop that is not displayed,
 * {@code softwareGl=auto} uses Mesa's llvmpipe here (see {@link MesaGl}).
 */
public final class Win32HiddenDesktop implements DisplayProvider {
    private final InstallContext ctx;

    public Win32HiddenDesktop(InstallContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "hidden-desktop";
    }

    @Override
    public DisplaySession open(String targetId, Profile.Display settings) throws IOException {
        String name = "verity-" + targetId.replaceAll("[^A-Za-z0-9-]", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);
        HANDLE desktop = Win32.User32.INSTANCE.CreateDesktopW(new WString(name), null, null, 0, Win32.GENERIC_ALL, null);
        if (desktop == null) {
            throw new IOException("CreateDesktop failed (error " + Win32.Kernel32.INSTANCE.GetLastError() + ")");
        }
        Map<String, String> env = new HashMap<>();
        boolean mesa = settings.softwareGl != Profile.SoftwareGl.OFF;
        if (mesa) env.put("GALLIUM_DRIVER", "llvmpipe");
        return new DisplaySession() {
            @Override
            public Map<String, String> environment() {
                return env;
            }

            @Override
            public String description() {
                return "hidden desktop " + name + (mesa ? " (Mesa llvmpipe)" : "");
            }

            @Override
            public GameProcess start(ProcessSpec spec) throws IOException {
                return launch(spec, "WinSta0\\" + name);
            }

            @Override
            public void close() {
                Win32.User32.INSTANCE.CloseDesktop(desktop);
            }
        };
    }

    /** Whether the game should use Mesa for this display setting. */
    public static boolean usesMesa(Profile.Display settings) {
        return settings.softwareGl != Profile.SoftwareGl.OFF;
    }

    static GameProcess launch(ProcessSpec spec, String desktop) throws IOException {
        Win32.Kernel32 k = Win32.Kernel32.INSTANCE;
        Win32.SecurityAttributes inherit = new Win32.SecurityAttributes(true);
        HANDLE out = k.CreateFileW(new WString(spec.outputFile().toString()), Win32.FILE_APPEND_DATA,
                Win32.FILE_SHARE_READ | Win32.FILE_SHARE_WRITE | Win32.FILE_SHARE_DELETE, inherit, Win32.OPEN_ALWAYS,
                Win32.FILE_ATTRIBUTE_NORMAL, null);
        HANDLE in = k.CreateFileW(new WString("NUL"), Win32.GENERIC_READ, Win32.FILE_SHARE_READ | Win32.FILE_SHARE_WRITE, inherit,
                Win32.OPEN_EXISTING, Win32.FILE_ATTRIBUTE_NORMAL, null);
        try {
            Win32.StartupInfo si = new Win32.StartupInfo();
            si.lpDesktop = new WString(desktop);
            si.dwFlags = Win32.STARTF_USESTDHANDLES;
            si.hStdInput = in;
            si.hStdOutput = out;
            si.hStdError = out;
            Win32.ProcessInformation pi = new Win32.ProcessInformation();
            Map<String, String> env = new HashMap<>(System.getenv());
            env.putAll(spec.environment());
            char[] cmd = (Win32.commandLine(spec.command()) + "\0").toCharArray();
            boolean ok = k.CreateProcessW(null, cmd, null, null, true,
                    Win32.CREATE_UNICODE_ENVIRONMENT | Win32.CREATE_NO_WINDOW, Win32.environmentBlock(env),
                    new WString(spec.workDir().toString()), si, pi);
            if (!ok) throw new IOException("CreateProcess failed (error " + k.GetLastError() + ")");
            k.CloseHandle(pi.hThread);
            return new DesktopProcess(pi.dwProcessId, pi.hProcess);
        } finally {
            if (out != null) k.CloseHandle(out);
            if (in != null) k.CloseHandle(in);
        }
    }

    private static final class DesktopProcess implements GameProcess {
        private final long pid;
        private final ProcessHandle handle;
        private final CompletableFuture<Integer> exit;

        DesktopProcess(long pid, HANDLE process) {
            this.pid = pid;
            this.handle = ProcessHandle.of(pid).orElse(null);
            CompletableFuture<?> ended = handle != null ? handle.onExit() : CompletableFuture.completedFuture(null);
            this.exit = ended.thenApply(h -> {
                IntByReference code = new IntByReference(-1);
                Win32.Kernel32.INSTANCE.GetExitCodeProcess(process, code);
                Win32.Kernel32.INSTANCE.CloseHandle(process);
                return code.getValue();
            });
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return handle != null && handle.isAlive();
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return exit;
        }

        @Override
        public void stop(Duration grace) {
            if (handle != null) JdkGameProcess.stopTree(handle, grace);
        }
    }
}
