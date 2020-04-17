package dev.javacontext.cancellable;

import dev.javacontext.context.Context;

public class CancellableContext {
  private static final Context.Key<Cancellation> CANCELLATION_KEY =
      Context.key("cancellable_context");

  /**
   * Returns the {@link Cancellation} from the current {@code Context}, falling back to a default,
   * no-op {@link Cancellation}.
   *
   * @return the {@link Cancellation} from the current {@code Context}.
   */
  public static Cancellation getCurrentCancellation() {
    return CANCELLATION_KEY.get(Context.current());
  }

  /**
   * Returns the {@link Cancellation} from the givent {@code Context}, falling back to a default,
   * no-op {@link Cancellation}.
   *
   * @return the {@link Cancellation} from the current {@code Context}.
   */
  public static Cancellation getCancellation(Context parent) {
    return CANCELLATION_KEY.get(parent);
  }
}
