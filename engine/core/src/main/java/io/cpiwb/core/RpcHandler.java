package io.cpiwb.core;

import java.util.Map;

/** Dispatches engine RPC methods such as {@code xslt.run}. */
public interface RpcHandler {

    Object call(String method, Map<String, Object> params) throws Exception;

    /** Browser calls use their own project context rather than the engine's open-project state. */
    default Object call(String method, Map<String, Object> params, ProjectFiles project) throws Exception {
        throw new UnsupportedOperationException("UnsupportedInSimulator: this engine does not support scoped project calls");
    }

    /** Engine identity reported in the stdio {@code ready} event and by {@code engine.info}. */
    Map<String, Object> describe();
}
