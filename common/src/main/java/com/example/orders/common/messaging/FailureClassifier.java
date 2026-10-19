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

    /**
     * The most useful text for logs and the DLQ reason attribute: the permanent exception if there
     * is one (it says why the message is unusable), otherwise the deepest cause.
     */
    public String describe(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PermanentMessageException) {
                return t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
