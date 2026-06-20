package su.grinev.pool;

public interface Disposable extends AutoCloseable {

    void setOnDispose(Runnable onDispose);

    /** Return this object to its pool for reuse (recycle). */
    void dispose();

    /** Permanently release the resources backing this object (e.g. native memory). */
    void destroy();

}
