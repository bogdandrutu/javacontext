package dev.javacontext.cancellable;

import static dev.javacontext.cancellable.Internal.checkNotNull;

import dev.javacontext.context.Context;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Contexts are also used to represent a scoped unit of work. When the unit of work is done the
 * context can be cancelled. This cancellation will also cascade to all descendant contexts. You can
 * add a {@link CancellationListener} to a {@code Cancellation} to be notified when it or one of its
 * ancestors has been cancelled. To cancel a context (and its descendants) you first create a {@link
 * CancellableCancellation} and when you need to signal cancellation call {@link
 * CancellableCancellation#cancel} or {@link CancellableCancellation#close}.
 *
 * <p>For example:
 *
 * <pre>
 *   CancellableCancellation withCancellation = CancellableCancellation.create(Context.current());
 *   try {
 *     withCancellation.getContext().run(new Runnable() {
 *       public void run() {
 *         while (waitingForData() &amp;&amp; !Context.current().isCancelled()) {}
 *       }
 *     });
 *     doSomeWork();
 *   } catch (Throwable t) {
 *      withCancellation.cancel(t);
 *   }
 * </pre>
 *
 * <p>Blocking code can use the try-with-resources idiom:
 *
 * <pre>
 * try (CancellableCancellation c = Context.current()
 *     .withDeadlineAfter(100, TimeUnit.MILLISECONDS, executor)) {
 *   Context toRestore = c.attach();
 *   try {
 *     // do some blocking work
 *   } finally {
 *     c.detach(toRestore);
 *   }
 * }</pre>
 *
 * <p>Contexts can also be created with a timeout relative to the system nano clock which will cause
 * it to automatically cancel at the desired time.
 *
 * <p>This class must be cancelled by either calling {@link #close} or {@link #cancel}. {@link
 * #close} is equivalent to calling {@code cancel(null)}. It is safe to call the methods more than
 * once, but only the first call will have any effect. Because it's safe to call the methods
 * multiple times, users are encouraged to always call {@link #close} at the end of the operation,
 * and disregard whether {@link #cancel} was already called somewhere else.
 *
 * <p>Asynchronous code will have to manually track the end of the CancellableCancellation's
 * lifetime, * and cancel the context at the appropriate time.
 */
public class CancellableCancellation implements Cancellation, Closeable {
  static final Logger log = Logger.getLogger(Cancellation.class.getName());

  private ArrayList<ExecutableListener> listeners;
  private final Cancellation parent;
  private final Deadline deadline;
  private final CancellationListener parentListener = new ParentListener();

  private boolean cancelled;
  private Throwable cancellationCause;
  private ScheduledFuture<?> pendingDeadline;

  /** Create a cancellable context that does not have a deadline. */
  private CancellableCancellation(Cancellation parent) {
    this.parent = parent;
    this.deadline = parent.getDeadline();
    // Create a surrogate that inherits from this to attach so that you cannot retrieve a
    // cancellable context from Context.current()
  }

  /** Create a cancellable context that has a deadline. */
  private CancellableCancellation(Cancellation parent, Deadline deadline) {
    this.parent = parent;
    this.deadline = deadline;
  }

  /**
   * Create a new context which is independently cancellable and also cascades cancellation from its
   * parent. Callers <em>must</em> ensure that either {@link
   * CancellableCancellation#cancel(Throwable)} or {@link CancellableCancellation#close()} are
   * called at a later point, in order to allow this {@code CancellableCancellation} to be garbage
   * collected.
   *
   * <p>Sample usage:
   *
   * <pre>
   *   CancellableCancellation withCancellation = CancellableCancellation.withCancellation(Context.current());
   *   try {
   *     withCancellation.getContext().run(new Runnable() {
   *       public void run() {
   *         Context current = Context.current();
   *         while (!current.isCancelled()) {
   *           keepWorking();
   *         }
   *       }
   *     });
   *   } finally {
   *     withCancellation.cancel(null);
   *   }
   * </pre>
   */
  public static CancellableCancellation create(Context parent) {
    return new CancellableCancellation(CancellableContext.getCancellation(parent));
  }

  /**
   * Create a new context which will cancel itself after the given {@code duration} from now. The
   * returned context will cascade cancellation of its parent. Callers may explicitly cancel the
   * returned context prior to the deadline just as for {@link #create(Context)}. If the unit of
   * work completes before the deadline, the context should be explicitly cancelled to allow it to
   * be garbage collected.
   *
   * <p>Sample usage:
   *
   * <pre>
   *   Context.CancellableCancellation withDeadline = Context.current()
   *       .withDeadlineAfter(5, TimeUnit.SECONDS, scheduler);
   *   try {
   *     withDeadline.run(new Runnable() {
   *       public void run() {
   *         Context current = Context.current();
   *         while (!current.isCancelled()) {
   *           keepWorking();
   *         }
   *       }
   *     });
   *   } finally {
   *     withDeadline.cancel(null);
   *   }
   * </pre>
   */
  public static CancellableCancellation createWithDeadline(
      Context parent, long duration, TimeUnit unit, ScheduledExecutorService scheduler) {
    return createWithDeadline(parent, Deadline.after(duration, unit), scheduler);
  }

  /**
   * Create a new context which will cancel itself at the given {@link Deadline}. The returned
   * context will cascade cancellation of its parent. Callers may explicitly cancel the returned
   * context prior to the deadline just as for {@link #create(Context)}. If the unit of work
   * completes before the deadline, the context should be explicitly cancelled to allow it to be
   * garbage collected.
   *
   * <p>Sample usage:
   *
   * <pre>
   *   CancellableCancellation withDeadline = CancellableCancellation
   *      .withDeadline(Context.current(), someReceivedDeadline, scheduler);
   *   try {
   *     withDeadline.getContext().run(new Runnable() {
   *       public void run() {
   *         Context current = Context.current();
   *         while (!current.isCancelled() &amp;&amp; moreWorkToDo()) {
   *           keepWorking();
   *         }
   *       }
   *     });
   *   } finally {
   *     withDeadline.cancel(null);
   *   }
   * </pre>
   */
  public static CancellableCancellation createWithDeadline(
      Context parent, Deadline newDeadline, ScheduledExecutorService scheduler) {
    checkNotNull(newDeadline, "deadline");
    checkNotNull(scheduler, "scheduler");
    Cancellation parentCancellation = CancellableContext.getCancellation(parent);
    Deadline existingDeadline = parentCancellation.getDeadline();
    boolean scheduleDeadlineCancellation = true;
    if (existingDeadline != null && existingDeadline.compareTo(newDeadline) <= 0) {
      // The new deadline won't have an effect, so ignore it
      newDeadline = existingDeadline;
      scheduleDeadlineCancellation = false;
    }
    CancellableCancellation cancellation = new CancellableCancellation(parentCancellation, newDeadline);
    if (scheduleDeadlineCancellation) {
      cancellation.setUpDeadlineCancellation(newDeadline, scheduler);
    }
    return cancellation;
  }

  void setUpDeadlineCancellation(Deadline deadline, ScheduledExecutorService scheduler) {
    if (!deadline.isExpired()) {
      final class CancelOnExpiration implements Runnable {
        @Override
        public void run() {
          try {
            cancel(new TimeoutException("context timed out"));
          } catch (Throwable t) {
            log.log(Level.SEVERE, "Cancel threw an exception, which should not happen", t);
          }
        }
      }

      synchronized (this) {
        pendingDeadline = deadline.runOnExpiration(new CancelOnExpiration(), scheduler);
      }
    } else {
      // Cancel immediately if the deadline is already expired.
      cancel(new TimeoutException("context timed out"));
    }
  }

  /**
   * Cancel this context and optionally provide a cause (can be {@code null}) for the cancellation.
   * This will trigger notification of listeners. It is safe to call this method multiple times.
   * Only the first call will have any effect.
   *
   * <p>Calling {@code cancel(null)} is the same as calling {@link #close}.
   *
   * @return {@code true} if this context cancelled the context and notified listeners, {@code
   *     false} if the context was already cancelled.
   */
  public boolean cancel(Throwable cause) {
    boolean triggeredCancel = false;
    synchronized (this) {
      if (!cancelled) {
        cancelled = true;
        if (pendingDeadline != null) {
          // If we have a scheduled cancellation pending attempt to cancel it.
          pendingDeadline.cancel(false);
          pendingDeadline = null;
        }
        this.cancellationCause = cause;
        triggeredCancel = true;
      }
    }
    if (triggeredCancel) {
      notifyAndClearListeners();
    }
    return triggeredCancel;
  }

  @Override
  public boolean isCancelled() {
    synchronized (this) {
      if (cancelled) {
        return true;
      }
    }
    // Detect cancellation of parent in the case where we have no listeners and
    // record it.
    if (parent != null && parent.isCancelled()) {
      cancel(parent.cause());
      return true;
    }
    return false;
  }

  @Override
  public Throwable cause() {
    if (isCancelled()) {
      return cancellationCause;
    }
    return null;
  }

  @Override
  public Deadline getDeadline() {
    return deadline;
  }

  @Override
  public void addListener(
      final CancellationListener cancellationListener, final Executor executor) {
    checkNotNull(cancellationListener, "cancellationListener");
    checkNotNull(executor, "executor");
    ExecutableListener executableListener =
        new ExecutableListener(executor, cancellationListener, this);
    synchronized (this) {
      if (isCancelled()) {
        executableListener.deliver();
      } else {
        if (listeners == null) {
          // Now that we have a listener we need to listen to our parent so
          // we can cascade listener notification.
          listeners = new ArrayList<>();
          listeners.add(executableListener);
          if (parent != null) {
            parent.addListener(parentListener, DirectExecutor.INSTANCE);
          }
        } else {
          listeners.add(executableListener);
        }
      }
    }
  }

  @Override
  public void removeListener(CancellationListener cancellationListener) {
    synchronized (this) {
      if (listeners != null) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
          if (listeners.get(i).listener == cancellationListener) {
            listeners.remove(i);
            // Just remove the first matching listener, given that we allow duplicate
            // adds we should allow for duplicates after remove.
            break;
          }
        }
        // We have no listeners so no need to listen to our parent
        if (listeners.isEmpty()) {
          if (parent != null) {
            parent.removeListener(parentListener);
          }
          listeners = null;
        }
      }
    }
  }

  @Override
  public void close() {
    cancel(null);
  }

  /**
   * Notify all listeners that this context has been cancelled and immediately release any
   * reference to them so that they may be garbage collected.
   */
  void notifyAndClearListeners() {
    ArrayList<ExecutableListener> tmpListeners;
    synchronized (this) {
      if (listeners == null) {
        return;
      }
      tmpListeners = listeners;
      listeners = null;
    }

    // Deliver events to non-child context listeners before we notify child contexts. We do this
    // to cancel higher level units of work before child units. This allows for a better error
    // handling paradigm where the higher level unit of work knows it is cancelled and so can
    // ignore errors that bubble up as a result of cancellation of lower level units.
    for (ExecutableListener tmpListener : tmpListeners) {
      if (!(tmpListener.listener instanceof ParentListener)) {
        tmpListener.deliver();
      }
    }
    for (ExecutableListener tmpListener : tmpListeners) {
      if (tmpListener.listener instanceof ParentListener) {
        tmpListener.deliver();
      }
    }
    if (parent != null) {
      parent.removeListener(parentListener);
    }
  }

  /** Stores listener and executor pair. */
  private static final class ExecutableListener implements Runnable {
    private final Executor executor;
    final CancellationListener listener;
    private final Cancellation cancellation;

    ExecutableListener(
        Executor executor, CancellationListener listener, Cancellation cancellation) {
      this.executor = executor;
      this.listener = listener;
      this.cancellation = cancellation;
    }

    void deliver() {
      try {
        executor.execute(this);
      } catch (Throwable t) {
        log.log(Level.INFO, "Exception notifying context listener", t);
      }
    }

    @Override
    public void run() {
      listener.cancelled(cancellation);
    }
  }

  // Used in tests to ensure that listeners are defined and released when cancellation cascades.
  // It's very important to ensure that we do not accidentally retain listeners.
  int listenerCount() {
    synchronized (this) {
      return listeners == null ? 0 : listeners.size();
    }
  }

  private enum DirectExecutor implements Executor {
    INSTANCE;

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public String toString() {
      return "Cancellation.DirectExecutor";
    }
  }

  private final class ParentListener implements CancellationListener {
    @Override
    public void cancelled(Cancellation cancellation) {
      // Record cancellation with its cancellationCause.
      Cancellation.this.cancel(cancellation.cause());
    }
  }
}
