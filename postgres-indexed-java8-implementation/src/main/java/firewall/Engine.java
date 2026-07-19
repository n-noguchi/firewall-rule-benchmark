package firewall;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the prebuilt policy index and answers First Match evaluation. The
 * engine is immutable once built so concurrent evaluations are safe.
 */
final class Engine {
    private final Map<String, TenantData> tenants;
    final String dataVersion;

    Engine(Map<String, TenantData> tenants, String dataVersion) {
        this.tenants = tenants;
        this.dataVersion = dataVersion;
    }

    /** Builds a copy of this engine that shares the immutable policy graph but reports a different data version. */
    Engine withDataVersion(String newDataVersion) {
        return new Engine(tenants, newDataVersion);
    }

    EvalResult evaluate(AccessLog access) {
        if (access.accessId == null || access.accessId.isEmpty()
                || access.sourceIpv4 == null || access.sourceIpv4.isEmpty()
                || access.urlPath == null || access.urlPath.isEmpty()
                || access.accessTimestampUtc == null || access.accessTimestampUtc.isEmpty()) {
            throw new IllegalArgumentException("access_id, source_ipv4, url_path, and access_timestamp_utc are required");
        }
        String[] scope = scopeFromPath(access.urlPath);
        String tenantId = scope[0];
        String departmentId = scope[1];
        TenantData tenant = tenants.get(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("no policy exists for tenant " + tenantId);
        }
        Policy policy = tenant.departments.get(departmentId);
        if (policy == null) {
            policy = tenant.policy;
        }
        if (policy == null) {
            throw new IllegalArgumentException("no policy exists for tenant " + tenantId);
        }
        int seconds = secondsOfDayUtc(access.accessTimestampUtc);

        Candidate best = policy.regex[Policy.SOURCE_IPV4].match(access.sourceIpv4, Candidate.EMPTY);
        if (best.isValid() && best.priority() == policy.minimum) {
            return matched(access.accessId, policy.id, best);
        }
        GroupSet set = tenant.departmentGroups.get(departmentId);
        if (set == null) {
            set = tenant.tenantGroups;
        }
        if (set != null) {
            List<String> keys = set.keysByAddress.get(access.sourceIpv4);
            if (keys != null) {
                for (String key : keys) {
                    Candidate value = policy.groupRules.get(key);
                    if (value != null) {
                        best = Candidate.earlier(best, value);
                    }
                }
            }
        }
        if (best.isValid() && best.priority() == policy.minimum) {
            return matched(access.accessId, policy.id, best);
        }
        best = policy.regex[Policy.URL_PATH].match(access.urlPath, best);
        best = policy.times.match(seconds, best);
        best = policy.regex[Policy.REFERER].match(access.referer == null ? "" : access.referer, best);
        best = policy.regex[Policy.USER_AGENT].match(access.userAgent == null ? "" : access.userAgent, best);
        if (best.isValid()) {
            return matched(access.accessId, policy.id, best);
        }
        return new EvalResult(access.accessId, policy.id, null, policy.defaultAction);
    }

    private static EvalResult matched(String accessId, String policyId, Candidate candidate) {
        return new EvalResult(accessId, policyId, candidate.ruleId(), candidate.action());
    }

    static String[] scopeFromPath(String path) {
        if (path.length() < 4 || path.charAt(0) != '/') {
            throw new IllegalArgumentException("URL path must begin /{tenant_id}/{department_id}/: " + path);
        }
        int first = indexOfByte(path, '/', 1);
        if (first <= 1) {
            throw new IllegalArgumentException("URL path must begin /{tenant_id}/{department_id}/: " + path);
        }
        int second = indexOfByte(path, '/', first + 1);
        if (second <= first + 1) {
            throw new IllegalArgumentException("URL path must begin /{tenant_id}/{department_id}/: " + path);
        }
        return new String[]{path.substring(1, first), path.substring(first + 1, second)};
    }

    private static int indexOfByte(String value, char target, int start) {
        for (int i = start; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static int secondsOfDayUtc(String timestamp) {
        Instant instant = Instant.parse(timestamp);
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        return ldt.toLocalTime().toSecondOfDay();
    }

    List<EvalResult> evaluateMany(List<AccessLog> accesses, int workers) {
        if (workers <= 0) {
            workers = Runtime.getRuntime().availableProcessors();
        }
        if (workers > accesses.size()) {
            workers = accesses.size();
        }
        if (workers < 2 || accesses.size() < 512) {
            List<EvalResult> results = new ArrayList<>(accesses.size());
            for (AccessLog access : accesses) {
                results.add(evaluate(access));
            }
            return results;
        }
        EvalResult[] results = new EvalResult[accesses.size()];
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>(workers);
            int chunk = (accesses.size() + workers - 1) / workers;
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            for (int w = 0; w < workers; w++) {
                int start = w * chunk;
                int end = Math.min(start + chunk, accesses.size());
                if (start >= end) {
                    break;
                }
                futures.add(executor.submit(() -> {
                    for (int i = start; i < end; i++) {
                        try {
                            results[i] = evaluate(accesses.get(i));
                        } catch (Throwable t) {
                            firstError.compareAndSet(null, t);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    firstError.compareAndSet(null, e);
                }
            }
            Throwable error = firstError.get();
            if (error != null) {
                throw new RuntimeException(error);
            }
            return Arrays.asList(results);
        } finally {
            executor.shutdownNow();
        }
    }
}
