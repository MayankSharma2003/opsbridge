package io.opsbridge.support.error;

import org.springframework.http.HttpStatus;

// Expected version no longer matches stored -- never last-write-wins, caller must re-read and retry.
public class StaleVersionException extends ApiException {

    public StaleVersionException(int expectedVersion, int actualVersion) {
        super(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                ("This incident was modified by someone else (expected version %d, current version %d). "
                                + "Reload it and try again.")
                        .formatted(expectedVersion, actualVersion));
    }

    public StaleVersionException() {
        super(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                "This incident was modified by someone else. Reload it and try again.");
    }
}
