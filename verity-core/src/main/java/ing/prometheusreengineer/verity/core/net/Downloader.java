package ing.prometheusreengineer.verity.core.net;

import ing.prometheusreengineer.verity.core.util.FileUtil;
import ing.prometheusreengineer.verity.core.util.Hashing;
import ing.prometheusreengineer.verity.core.util.Progress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel, verifying, retrying downloader. Files land via temp file + atomic rename; concurrent requests for
 * the same path (e.g. two targets sharing a library) are coalesced.
 */
public final class Downloader {
    private static final int ATTEMPTS = 3;

    private final Http http;
    private final Semaphore permits;
    private final ConcurrentHashMap<Path, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    public Downloader(Http http, int parallelism) {
        this.http = http;
        this.permits = new Semaphore(Math.max(1, parallelism));
    }

    public Http http() {
        return http;
    }

    /**
     * A file to fetch. {@code urls} are tried in order (mirrors). {@code sha1}/{@code size} may be null/-1 when
     * unknown. {@code hashExisting=false} trusts an existing file of the right size (used for
     * content-addressed assets).
     */
    public record Artifact(List<URI> urls, Path target, String sha1, long size, boolean hashExisting) {
        public static Artifact of(String url, Path target, String sha1, long size) {
            return new Artifact(List.of(URI.create(url)), target, sha1, size, true);
        }

        public String name() {
            return target.getFileName().toString();
        }
    }

    public boolean isPresent(Artifact a) throws IOException {
        if (!Files.isRegularFile(a.target())) return false;
        if (a.size() >= 0 && Files.size(a.target()) != a.size()) return false;
        if (a.sha1() == null) return a.size() >= 0 || Files.size(a.target()) > 0;
        if (!a.hashExisting() && a.size() >= 0) return true;
        return a.sha1().equalsIgnoreCase(Hashing.sha1(a.target()));
    }

    public void download(Artifact a) throws IOException {
        if (isPresent(a)) return;
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> existing = inFlight.putIfAbsent(a.target(), mine);
        if (existing != null) {
            join(existing);
            return;
        }
        try {
            permits.acquireUninterruptibly();
            try {
                fetch(a);
            } finally {
                permits.release();
            }
            mine.complete(null);
        } catch (IOException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(a.target(), mine);
        }
    }

    public void downloadAll(Collection<Artifact> artifacts, Progress progress) throws IOException {
        Map<Path, Artifact> unique = new LinkedHashMap<>();
        for (Artifact a : artifacts) unique.putIfAbsent(a.target(), a);
        long total = unique.size();
        AtomicLong done = new AtomicLong();
        progress.progress(0, total);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Artifact a : unique.values()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        download(a);
                    } catch (IOException e) {
                        throw new DownloadException(a, e);
                    }
                    progress.progress(done.incrementAndGet(), total);
                }, ex));
            }
            join(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)));
        }
    }

    private void fetch(Artifact a) throws IOException {
        IOException last = null;
        for (URI url : a.urls()) {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    fetchOnce(url, a);
                    return;
                } catch (Http.HttpStatusException e) {
                    last = e;
                    if (e.status() == 404 || e.status() == 403 || e.status() == 410) break; // next mirror
                    // 429 (rate limited) falls through to the backoff below.
                } catch (IOException e) {
                    last = e;
                }
                if (attempt < ATTEMPTS) sleep(500L * attempt * attempt);
            }
        }
        throw new IOException("Failed to download " + a.name() + " from " + a.urls()
                + (last != null ? ": " + last.getMessage() : ""), last);
    }

    private void fetchOnce(URI url, Artifact a) throws IOException {
        Path target = a.target();
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            MessageDigest sha1 = sha1();
            long size = 0;
            try (InputStream in = http.getStream(url); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha1.update(buf, 0, n);
                    size += n;
                }
            }
            if (a.size() >= 0 && size != a.size()) {
                throw new IOException("Size mismatch for " + url + ": expected " + a.size() + ", got " + size);
            }
            String actual = HexFormat.of().formatHex(sha1.digest());
            if (a.sha1() != null && !a.sha1().equalsIgnoreCase(actual)) {
                throw new IOException("SHA-1 mismatch for " + url + ": expected " + a.sha1() + ", got " + actual);
            }
            FileUtil.moveAtomically(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(CompletableFuture<?> f) throws IOException {
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof DownloadException de) throw de.unwrap();
            if (c instanceof IOException io) throw io;
            if (c instanceof RuntimeException re) throw re;
            throw new IOException(c);
        }
    }

    /** Unchecked carrier so lambdas can propagate IO failures. */
    public static final class DownloadException extends RuntimeException {
        DownloadException(Artifact a, IOException cause) {
            super(cause.getMessage(), cause);
        }

        IOException unwrap() {
            return (IOException) getCause();
        }
    }
}
