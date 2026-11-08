package com.relayforge.simulator;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the simulator's deliberate failures into the right wire behaviour. */
@RestControllerAdvice
public class SimulatorErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulatorErrorHandler.class);

    /**
     * Handles the dropped-connection scenario.
     *
     * <p>Resets the response buffer and closes the stream without writing a
     * status line, so the client observes a transport failure rather than an HTTP
     * error. The distinction matters: the CONNECTION_DROP scenario exists to
     * exercise RelayForge's no-status code path, and returning any status —
     * including 502 — would take the wrong branch of the classifier and prove
     * nothing.
     */
    @ExceptionHandler(SimulatorController.ConnectionDropException.class)
    public void handleConnectionDrop(
            SimulatorController.ConnectionDropException e, HttpServletResponse response)
            throws IOException {
        log.info("Dropping connection as configured: {}", e.getMessage());
        if (!response.isCommitted()) {
            response.resetBuffer();
        }
        response.getOutputStream().close();
    }
}
