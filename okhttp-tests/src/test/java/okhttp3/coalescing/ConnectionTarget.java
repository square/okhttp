package okhttp3.coalescing;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConnectionTarget {
  private Map<String, String> configuredTargets = new LinkedHashMap<>();
  private boolean autoCoalescing;

  public ConnectionTarget() {
  }

  public void addTarget(String destination, String target) {
    configuredTargets.put(destination, target);
  }

  public String getTarget(String host) {
    for (Map.Entry<String, String> e : configuredTargets.entrySet()) {
      if (matches(e.getKey(), host)) {
        return e.getValue();
      }
    }

    return null;
  }

  public static boolean matches(String key, String host) {
    if (key.equals(host)) {
      return true;
    }

    if (key.startsWith("*.")) {
      String parentHost = key.substring(2);

      return parentHost.equals(host) || host.endsWith("." + parentHost);
    }

    return false;
  }

  public void setAutoCoalescing(boolean autoCoalescing) {
    this.autoCoalescing = autoCoalescing;
  }

  public boolean isAutoCoalescing() {
    return autoCoalescing;
  }
}
