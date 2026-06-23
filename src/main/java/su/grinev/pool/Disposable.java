package su.grinev.pool;

public interface Disposable extends AutoCloseable {

    void setOnDispose(Runnable onDispose);

    /** The current dispose hook, or null if none set — lets a pool set it once per object, not per checkout. */
    Runnable getOnDispose();

    /** Return this object to its pool for reuse (recycle). */
    void dispose();

    /** Permanently release the resources backing this object (e.g. native memory). */
    void destroy();

}
