package com.example.orders.common.messaging;

/** Decides whether a consumer failure is worth retrying. */
public class FailureClassifier {

    /** True when the failure (or anything in its cause chain) is a {@link PermanentMessageException}. */
    public boolean isPermanent(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PermanentMessageException) {
                return true;
            }
        }
        return false;
    }

    /** The most relevant message for logs: the deepest cause's message. */
    public String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
