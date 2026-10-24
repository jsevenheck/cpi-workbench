package io.cpiwb.core;

import java.util.Map;

/** Dispatches engine RPC methods such as {@code xslt.run}. */
public interface RpcHandler {

    Object call(String method, Map<String, Object> params) throws Exception;

    /** Engine identity reported in the stdio {@code ready} event and by {@code engine.info}. */
    Map<String, Object> describe();
}
