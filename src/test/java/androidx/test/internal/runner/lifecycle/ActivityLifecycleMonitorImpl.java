package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
  private final java.util.WeakHashMap<android.app.Activity, Stage> map = new java.util.WeakHashMap<>();
  public void signalLifecycleChange(Stage s, android.app.Activity a) { map.put(a, s); }
  public Stage getLifecycleStageOf(android.app.Activity a) { return map.get(a); }
}
