import java.util.Date;

public class Logger {
    public static void info(String msg){
        System.out.println("\u001B[32m" + "INFO:" + " " + new Date() + " " + msg + "\u001B[0m");
    }

    public static void warning(String msg){
        System.out.println("\u001B[33m" + "WARNING:" + " " + new Date() + " " + msg + "\u001B[0m");
    }

    public static void severe(String msg){
        System.out.println("\u001B[31m" + "SEVERE:" + " " + new Date() + " " + msg + "\u001B[0m");
    }
}
