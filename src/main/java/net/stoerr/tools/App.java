package net.stoerr.tools;

import java.util.Arrays;

/**
 * Hello world!
 *
 */
public class App {
    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0 && "--tango".equals(args[0])) {
            // forward remaining args to PrintTangoConcerts3.main
            String[] forwarded = Arrays.copyOfRange(args, 1, args.length);
            PrintTangoConcerts4.main(forwarded);
        }
    }
}
