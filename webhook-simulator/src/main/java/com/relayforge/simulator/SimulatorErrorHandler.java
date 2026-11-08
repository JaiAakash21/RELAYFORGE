package com.relayforge.simulator;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the simulator's deliberate failures into the right behaviour on the wire. */
@RestControllerAdvice
public class SimulatorErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulatorErrorHandler.class);

    /**
     * Implements the dropped-connection scenario.
     *
     * <h2>Why this is more involved than it looks</h2>
     *
     * The obvious implementation — throw, and let the container deal with it —
     * does not work. Spring answers an unhandled exception with an HTTP status,
     * and even resetting the buffer and closing the stream leaves the container
     * free to emit a complete {@code 200}. The client then classifies the attempt
     * by that status, which is precisely the branch this scenario is meant to
     * avoid: it exists to exercise the path where there is <em>no</em> status at
     * all.
     *
     * <p>So the response is made deliberately truncated instead: a
     * {@code Content-Length} is declared, fewer bytes than that are written, and
     * the connection is closed. The client reads a premature end of stream and
     * raises a transport-level error — a real {@code ConnectionClosedException},
     * indistinguishable from a receiver whose process died mid-response, which is
     * exactly the condition being simulated.
     *
     * <p>{@code Connection: close} is set so the socket is not returned to the
     * keep-alive pool in a half-read state, where it would poison an unrelated
     * later request.
     */
    @ExceptionHandler(SimulatorController.ConnectionDropException.class)
    public void handleConnectionDrop(
            SimulatorController.ConnectionDropException e, HttpServletResponse response)
            throws IOException {
        log.info("Truncating the response to simulate a dropped connection: {}", e.getMessage());

        if (response.isCommitted()) {
            // Nothing further can be done to the status line; closing the stream
            // is still the closest available approximation.
            response.getOutputStream().close();
            return;
        }

        response.resetBuffer();
        response.setStatus(200);
        response.setContentType("application/json");
        response.setHeader(HttpHeaders.CONNECTION, "close");
        // Promise far more than will be written.
        response.setContentLength(4096);

        OutputStream out = response.getOutputStream();
        out.write("{\"simulator\":\"connection drop".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
        // Closing now leaves the declared body unfinished.
        out.close();
    }
}
