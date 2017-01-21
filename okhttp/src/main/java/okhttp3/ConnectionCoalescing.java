package okhttp3;

import java.util.ArrayList;
import java.util.List;

public class ConnectionCoalescing {
  public static final ConnectionCoalescing NONE = new ConnectionCoalescing.Builder().build();

  private List<Target> targets = new ArrayList<>();
  private boolean autoCoalescing;

  public ConnectionCoalescing(Builder builder) {
    this.targets = new ArrayList<>(builder.targets);
    this.autoCoalescing = builder.autoCoalescing;
  }

  public String getTarget(String host) {
    for (Target target : targets) {
      if (target.matches(host)) {
        return target.destination;
      }
    }

    return host;
  }

  public void setAutoCoalescing(boolean autoCoalescing) {
    this.autoCoalescing = autoCoalescing;
  }

  public boolean isAutoCoalescing() {
    return autoCoalescing;
  }

  public boolean isDefault() {
    return !isAutoCoalescing() && targets.isEmpty();
  }

  public static final class Target {
    public final String host;
    public final String destination;
    public final boolean wildcard;

    public Target(String host, String destination, boolean wildcard) {
      this.host = host;
      this.destination = destination;
      this.wildcard = wildcard;
    }

    public boolean matches(String urlHost) {
      if (host.equals(urlHost)) {
        return true;
      }

      if (wildcard) {
        return urlHost.endsWith("." + host);
      }

      return false;
    }
  }

  public static final class Builder {
    private List<Target> targets = new ArrayList<>();
    private boolean autoCoalescing;

    public Builder setAutoCoalescing(boolean autoCoalescing) {
      this.autoCoalescing = autoCoalescing;
      return this;
    }

    public Builder addTarget(Target target) {
      this.targets.add(target);
      return this;
    }

    public ConnectionCoalescing build() {
      return new ConnectionCoalescing(this);
    }

    public Builder addSingleHost(String host, String destination) {
      return addTarget(new Target(host, destination, false));
    }

    public Builder addWildcard(String host, String destination) {
      return addTarget(new Target(host, destination, true));
    }
  }
}
