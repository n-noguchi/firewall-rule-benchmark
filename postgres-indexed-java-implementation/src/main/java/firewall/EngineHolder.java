package firewall;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe holder of the live engine snapshot. */
final class EngineHolder {
    private final AtomicReference<Engine> engine;

    EngineHolder(Engine initial) {
        this.engine = new AtomicReference<>(initial);
    }

    Engine get() {
        return engine.get();
    }

    void set(Engine next) {
        engine.set(next);
    }
}
