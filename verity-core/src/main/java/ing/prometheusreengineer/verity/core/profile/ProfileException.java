package ing.prometheusreengineer.verity.core.profile;

/** A problem with a profile the user must fix (bad path, unknown version...). */
public class ProfileException extends Exception {
    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
