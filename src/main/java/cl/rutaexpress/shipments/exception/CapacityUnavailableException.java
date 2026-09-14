package cl.rutaexpress.shipments.exception;

public class CapacityUnavailableException extends RuntimeException {
    public CapacityUnavailableException(String message) {
        super(message);
    }
}
