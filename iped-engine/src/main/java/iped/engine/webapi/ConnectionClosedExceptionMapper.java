package iped.engine.webapi;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.glassfish.jersey.server.internal.process.MappableException;

/**
 * Exception mapper to handle I/O errors that occur when clients close
 * connections prematurely. This prevents SEVERE log messages for normal
 * client behavior (e.g., navigating away, closing browser tabs).
 */
@Provider
public class ConnectionClosedExceptionMapper implements ExceptionMapper<MappableException> {

    private static final Logger LOGGER = Logger.getLogger(ConnectionClosedExceptionMapper.class.getName());

    @Override
    public Response toResponse(MappableException exception) {
        Throwable cause = exception.getCause();
        
        // Check if this is a connection closed error
        if (isConnectionClosedException(cause)) {
            LOGGER.log(Level.FINE, "Client closed connection before response could be sent", exception);
            // Return 499 (Client Closed Request) - non-standard but used by nginx
            // The client won't receive this anyway, so the status code is just for logging
            return Response.status(499).build();
        }
        
        // For other MappableExceptions, log and return 500
        LOGGER.log(Level.SEVERE, "Unexpected I/O error while writing response", exception);
        return Response.serverError().build();
    }

    /**
     * Checks if the exception is caused by a closed connection.
     */
    private boolean isConnectionClosedException(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof IOException) {
                String message = throwable.getMessage();
                if (message != null && (
                        message.contains("Connection is closed") ||
                        message.contains("connection was aborted") ||
                        message.contains("conexão estabelecida foi anulada") ||
                        message.contains("An established connection was aborted") ||
                        message.contains("Broken pipe") ||
                        message.contains("Connection reset") ||
                        (message.contains("Seek to") && message.contains("failed")))) {
                    return true;
                }
            }
            throwable = throwable.getCause();
        }
        return false;
    }
}
