package okhttp3.mockwebserver;

import static java.lang.System.currentTimeMillis;

public class WaitForCondition {
    public static final int POLLING_INTERVAL = 500;

    public static void waitFor(Condition condition, long maxWaitInMillis) {
        long startTime = currentTimeMillis();
        while(!condition.isSatisfied() && currentTimeMillis() - startTime < maxWaitInMillis) {
            try {
                Thread.sleep(POLLING_INTERVAL);
            } catch (InterruptedException ignored) {

            }
        }

        if(!condition.isSatisfied()) throw new RuntimeException("Timed out waiting for condition to be satisfied!");
    }

    public interface Condition {
        boolean isSatisfied();
    }

}
