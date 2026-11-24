package com.ashford.terminal.reconcile;

/**
 * Per-field provenance. The first four answer "can I trust this value as current?":
 * UPDATED          - valid value from the latest extraction (and its source is recent enough)
 * CARRIED_FORWARD  - the latest run did not mention it; an earlier value is retained, NOT re-verified
 * STALE            - a value that exists but is older than the staleness threshold
 * The last three mean "there is no new value, and here is why":
 * EXTRACTION_FAILED- the extraction call for this section failed (an older value, if any, is retained)
 * REJECTED_INVALID - the extraction returned something for this field that failed validation
 * MISSING          - never populated; the source did not mention it
 */
public enum Status { UPDATED, CARRIED_FORWARD, STALE, EXTRACTION_FAILED, REJECTED_INVALID, MISSING }
