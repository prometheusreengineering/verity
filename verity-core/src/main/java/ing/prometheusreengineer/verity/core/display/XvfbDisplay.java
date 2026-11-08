package ing.prometheusreengineer.verity.core.display;

import ing.prometheusreengineer.verity.core.profile.Profile;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One private Xvfb server per client: real OpenGL rendering (Mesa) with nothing on screen. */
public final class XvfbDisplay implements DisplayProvider {
    @Override
    public String name() {
        return "xvfb";
    }

    static Path which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            Path p = Path.of(dir, exe);
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }

    @Override
    public void preflight() throws IOException {
        if (which("Xvfb") == null) {
            throw new IOException("Headless mode needs Xvfb, which was not found on PATH. Install it "
                    + "(Debian/Ubuntu: sudo apt install xvfb libgl1-mesa-dri x11-xserver-utils; Fedora: sudo dnf install "
                    + "xorg-x11-server-Xvfb mesa-dri-drivers xrandr) or run with --display window.");
        }
    }

    @Override
    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        if (which("xrandr") == null) {
            w.add("xrandr is not installed; Minecraft 1.12.2 and older (LWJGL 2) crash at startup without it "
                    + "(Debian/Ubuntu: sudo apt install x11-xserver-utils).");
        }
        return w;
    }

    static List<String> command(Path xvfb, int width, int height) {
        return List.of(xvfb.toString(), "-displayfd", "1", "-screen", "0", width + "x" + height + "x24",
                "-nolisten", "tcp", "-noreset", "+extension", "GLX", "+extension", "RANDR", "+render");
    }

    @Override
    public DisplaySession open(String targetId, Profile.Display settings) throws IOException {
        preflight();
        Path xvfb = which("Xvfb");
        int w = Math.max(1280, settings.width + 64);
        int h = Math.max(800, settings.height + 64);
        Process p = new ProcessBuilder(command(xvfb, w, h)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String display;
        try {
            display = CompletableFuture.supplyAsync(() -> {
                try {
                    return r.readLine();
                } catch (IOException e) {
                    return null;
                }
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            display = null;
        }
        if (display == null || display.isBlank()) {
            p.destroyForcibly();
            throw new IOException("Xvfb did not start (no display number reported)");
        }
        String number = display.trim();
        Map<String, String> env = new HashMap<>();
        env.put("DISPLAY", ":" + number);
        env.put("vblank_mode", "0");
        env.put("__GL_SYNC_TO_VBLANK", "0");
        if (settings.softwareGl != Profile.SoftwareGl.OFF) {
            env.put("LIBGL_ALWAYS_SOFTWARE", "1");
            env.put("GALLIUM_DRIVER", "llvmpipe");
        }
        // Minecraft 26.3+ creates its window through SDL3 and asks for an sRGB-capable framebuffer, which Xvfb's GLX
        // visuals don't offer ("Couldn't find matching GLX visual"). Mesa's EGL on X11 does. GLFW versions ignore this.
        env.put("SDL_VIDEO_FORCE_EGL", "1");
        return new DisplaySession() {
            @Override
            public Map<String, String> environment() {
                return env;
            }

            @Override
            public String description() {
                return "Xvfb :" + number;
            }

            @Override
            public void close() {
                JdkGameProcess.stopTree(p.toHandle(), Duration.ofSeconds(3));
            }
        };
    }
}
