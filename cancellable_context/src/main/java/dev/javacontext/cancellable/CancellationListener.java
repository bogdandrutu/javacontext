package dev.javacontext.cancellable;

/** A listener notified on context cancellation. */
public interface CancellationListener {
  /** @param cancellation the newly cancelled context. */
  void cancelled(Cancellation cancellation);
}
