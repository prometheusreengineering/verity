package dev.verity.core.run;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Everything about one run; written as report.json/junit.xml/report.html. */
public class RunReport {
    public String profile;
    public String verityVersion = "0.1.0";
    public String host;
    public Instant startedAt;
    public Instant finishedAt;
    public String runDir;
    public boolean prepareOnly;
    public List<TargetResult> results = new ArrayList<>();

    @JsonIgnore
    public boolean success() {
        return !results.isEmpty() && results.stream().allMatch(TargetResult::success);
    }

    public long count(Status s) {
        return results.stream().filter(r -> r.status == s).count();
    }

    public long passed() {
        return results.stream().filter(TargetResult::success).count();
    }
}
