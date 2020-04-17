package dev.javacontext.cancellable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.google.common.util.concurrent.MoreExecutors;
import dev.javacontext.context.Context;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;

public class CancellableCancellationTest {
  private Cancellation listenerNotifiedCancellation;
  private CountDownLatch deadlineLatch = new CountDownLatch(1);
  private CancellationListener cancellationListener =
      new CancellationListener() {
        @Override
        public void cancelled(Cancellation cancellation) {
          listenerNotifiedCancellation = cancellation;
          deadlineLatch.countDown();
        }
      };

  private Context observed;
  private Runnable runner =
      new Runnable() {
        @Override
        public void run() {
          observed = Context.current();
        }
      };
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @Test
  public void rootCanNeverHaveAListener() {
    Context root = Context.ROOT;
    Cancellation rootCancellation = CancellableCancellation.getCurrentCancellation();
    rootCancellation.addListener(cancellationListener, MoreExecutors.directExecutor());
    assertEquals(0, rootCancellation.listenerCount());
  }

  @Test
  public void rootIsNotCancelled() {
    assertFalse(Context.ROOT.isCancelled());
    assertNull(Context.ROOT.cancellationCause());
  }
}
