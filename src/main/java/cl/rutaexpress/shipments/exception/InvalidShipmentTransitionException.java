package cl.rutaexpress.shipments.exception;

public class InvalidShipmentTransitionException extends RuntimeException {
    public InvalidShipmentTransitionException(String message) {
        super(message);
    }
}
