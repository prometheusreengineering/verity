package dev.verity.core.display;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal Win32 bindings for launching a process on another desktop. Loaded lazily, only on Windows. */
final class Win32 {
    static final int GENERIC_ALL = 0x10000000;
    static final int STARTF_USESTDHANDLES = 0x00000100;
    static final int STARTF_USESHOWWINDOW = 0x00000001;
    static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    static final int CREATE_NO_WINDOW = 0x08000000;
    static final int FILE_APPEND_DATA = 0x0004;
    static final int GENERIC_READ = 0x80000000;
    static final int FILE_SHARE_READ = 0x1;
    static final int FILE_SHARE_WRITE = 0x2;
    static final int FILE_SHARE_DELETE = 0x4;
    static final int OPEN_ALWAYS = 4;
    static final int OPEN_EXISTING = 3;
    static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    static final int SYNCHRONIZE = 0x00100000;
    static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private Win32() {
    }

    interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        HANDLE CreateDesktopW(WString name, Pointer device, Pointer devmode, int flags, int access, SecurityAttributes sa);

        boolean CloseDesktop(HANDLE desktop);
    }

    interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        boolean CreateProcessW(WString app, char[] commandLine, SecurityAttributes processAttrs, SecurityAttributes threadAttrs,
                               boolean inheritHandles, int flags, Pointer environment, WString cwd, StartupInfo startup,
                               ProcessInformation info);

        HANDLE CreateFileW(WString name, int access, int share, SecurityAttributes sa, int disposition, int flags, Pointer template);

        boolean CloseHandle(HANDLE h);

        boolean GetExitCodeProcess(HANDLE process, IntByReference exitCode);

        int GetLastError();
    }

    @Structure.FieldOrder({"nLength", "lpSecurityDescriptor", "bInheritHandle"})
    public static class SecurityAttributes extends Structure {
        public int nLength;
        public Pointer lpSecurityDescriptor;
        public boolean bInheritHandle;

        SecurityAttributes(boolean inherit) {
            nLength = size();
            bInheritHandle = inherit;
        }
    }

    @Structure.FieldOrder({"cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize", "dwXCountChars",
            "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow", "cbReserved2", "lpReserved2", "hStdInput",
            "hStdOutput", "hStdError"})
    public static class StartupInfo extends Structure {
        public int cb;
        public WString lpReserved;
        public WString lpDesktop;
        public WString lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public Pointer lpReserved2;
        public HANDLE hStdInput;
        public HANDLE hStdOutput;
        public HANDLE hStdError;

        StartupInfo() {
            cb = size();
        }
    }

    @Structure.FieldOrder({"hProcess", "hThread", "dwProcessId", "dwThreadId"})
    public static class ProcessInformation extends Structure {
        public HANDLE hProcess;
        public HANDLE hThread;
        public int dwProcessId;
        public int dwThreadId;
    }

    /** Quotes one argument following the MSVC runtime's parsing rules. */
    static String quote(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) return arg;
        StringBuilder b = new StringBuilder("\"");
        int backslashes = 0;
        for (char c : arg.toCharArray()) {
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                b.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                b.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        b.append("\\".repeat(backslashes * 2)).append('"');
        return b.toString();
    }

    static String commandLine(List<String> args) {
        StringBuilder b = new StringBuilder();
        for (String a : args) {
            if (b.length() > 0) b.append(' ');
            b.append(quote(a));
        }
        return b.toString();
    }

    /** Builds a sorted UTF-16 environment block. */
    static Memory environmentBlock(Map<String, String> env) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(env);
        StringBuilder b = new StringBuilder();
        sorted.forEach((k, v) -> b.append(k).append('=').append(v).append('\0'));
        b.append('\0');
        byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory m = new Memory(bytes.length + 2L);
        m.clear();
        m.write(0, bytes, 0, bytes.length);
        return m;
    }
}
