package com.sap.it.api.mapping;

/**
 * The result of a custom function with the execution type "All values of a context" or "All values of
 * a queue". Methods follow SAP's published Javadoc of the Cloud Integration API
 * ({@code com.sap.it.api.mapping}): {@code isContextChange(a[i])} finds context changes in an input
 * array.
 */
public interface Output {

    /** Appends a value. */
    void addValue(Object value);

    /** Appends a context change. */
    void addContextChange();

    /** Appends SUPPRESS: the target field and its sub nodes are not generated for this entry. */
    void addSuppress();

    /** Appends an xsi:nil value. */
    void addXSINIL();

    /** Deletes all values appended so far. */
    void clear();

    boolean isContextChange(Object value);

    boolean isSuppress(Object value);

    boolean isXSINIL(Object value);
}
