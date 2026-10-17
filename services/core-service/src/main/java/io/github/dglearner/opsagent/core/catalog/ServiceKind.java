package io.github.dglearner.opsagent.core.catalog;

/** What a catalog entry is; anything that can fail and be depended on is in the catalog, not only services. */
public enum ServiceKind {
    SERVICE, DATABASE, CACHE, QUEUE, EXTERNAL
}
