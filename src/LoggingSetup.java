import java.io.IOException;
import java.util.logging.*;

public class LoggingSetup {
    public static void configureLogger(Logger logger) {
        try {
            FileHandler fh = new FileHandler("GopherIndexer.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.ALL); // Set the default logging level
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not set up file handler for logger", e);
        }
    }
}