package firewall;

import java.util.HashMap;
import java.util.Map;

final class TenantData {
    Policy policy;
    final Map<String, Policy> departments = new HashMap<>();
    GroupSet tenantGroups;
    final Map<String, GroupSet> departmentGroups = new HashMap<>();
}
