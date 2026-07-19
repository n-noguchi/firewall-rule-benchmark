package firewall;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tenant-scoped or department-scoped set of source IP groups. */
final class GroupSet {
    final Map<String, List<String>> keysByAddress = new HashMap<>();
    final Set<String> definedKeys = new HashSet<>();

    boolean isEmpty() {
        return definedKeys.isEmpty() && keysByAddress.isEmpty();
    }
}
