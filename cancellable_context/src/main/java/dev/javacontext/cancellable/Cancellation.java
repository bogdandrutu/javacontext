package dev.javacontext.cancellable;

import static dev.javacontext.cancellable.Internal.checkNotNull;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A context which inherits cancellation from its parent but which can also be independently
 * cancelled and which will propagate cancellation to its descendants. To avoid leaking memory,
 * every CancellableContext must have a defined lifetime, after which it is guaranteed to be
 * cancelled.
 *
 * <p>This class must be cancelled by either calling {@link #close} or {@link #cancel}. {@link
 * #close} is equivalent to calling {@code cancel(null)}. It is safe to call the methods more than
 * once, but only the first call will have any effect. Because it's safe to call the methods
 * multiple times, users are encouraged to always call {@link #close} at the end of the operation,
 * and disregard whether {@link #cancel} was already called somewhere else.
 *
 * <p>Blocking code can use the try-with-resources idiom:
 *
 * <pre>
 * try (CancellableContext c = Context.current()
 *     .withDeadlineAfter(100, TimeUnit.MILLISECONDS, executor)) {
 *   Context toRestore = c.attach();
 *   try {
 *     // do some blocking work
 *   } finally {
 *     c.detach(toRestore);
 *   }
 * }</pre>
 *
 * <p>Asynchronous code will have to manually track the end of the CancellableContext's lifetime,
 * and cancel the context at the appropriate time.
 */
public interface Cancellation {

  /** Is this {@code Cancellation} cancelled. */
  boolean isCancelled();

  /**
   * If a {@code Cancellation} {@link #isCancelled()} then return the cause of the cancellation or
   * {@code null} if Cancellation was cancelled without a cause. If the context is not yet
   * cancelled will always return {@code null}.
   *
   * <p>The cancellation cause is provided for informational purposes only and implementations
   * should generally assume that it has already been handled and logged properly.
   */
  Throwable cause();

  /**
   * A {@code Cancellation} may have an associated {@link Deadline} at which it will be
   * automatically cancelled.
   *
   * @return A {@link Deadline} or {@code null} if no deadline is set.
   */
  Deadline getDeadline();

  /** Add a listener that will be notified when the context becomes cancelled. */
  void addListener(
      final CancellationListener cancellationListener, final Executor executor);

  /** Remove a {@link CancellationListener}. */
  void removeListener(CancellationListener cancellationListener);
}
