import java.util.Date;

/**
 * The Logger class provides static methods to log information, warnings, and severe error messages
 * with ANSI color coding for visibility. Each log entry includes a timestamp to help trace events chronologically.
 */
public class Logger {

    /**
     * Logs informational messages with a green color.
     * This method should be used to indicate successful operations or important state changes in the application.
     * @param msg The message to be logged.
     */

    public static void info(String msg){
        System.out.println("\u001B[32m" + "INFO:" + " " + new Date() + " " + msg + "\u001B[0m");
    }


    /**
     * Logs warning messages with a yellow color.
     * This method should be used to indicate potential issues or unexpected behavior in the application.
     * @param msg The message to be logged.
     */
    public static void warning(String msg){
        System.out.println("\u001B[33m" + "WARNING:" + " " + new Date() + " " + msg + "\u001B[0m");
    }

    /**
     * Logs severe error messages with a red color.
     * This method should be used to indicate critical failures or unexpected exceptions in the application.
     * @param msg The message to be logged.
     */
    public static void severe(String msg){
        System.out.println("\u001B[31m" + "SEVERE:" + " " + new Date() + " " + msg + "\u001B[0m");
    }
}
