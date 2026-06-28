package su.grinev.exception;

/**
 * Thrown during binding when a field violates a declared constraint (e.g. {@code @Size(min/max)}).
 * Distinct from other decode errors so callers can react to abusive/malformed input specifically —
 * reply with an error status, drop the message, or close the connection.
 */
public class ValidationException extends BsonException {
    public ValidationException(String message) {
        super(message);
    }
}
