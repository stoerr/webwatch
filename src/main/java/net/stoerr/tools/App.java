package net.stoerr.tools;

import java.util.Arrays;

/**
 * Switch between tools, depending on first argument.
 * --tango means PrintTangoConcerts , --checkpages means CheckWebPagesForChangesWithTextExtract
 */
public class App {
    public static void printUsage() {
        System.out.println("Usage: java -jar tools.jar --tango [args for PrintTangoConcerts]");
        System.out.println("   or: java -jar tools.jar --checkpages [args for CheckWebPagesForChangesWithTextExtract]");
        System.out.println("   or: java -jar tools.jar --searchjobs [args for SearchJobs]");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
            return;
        }
        String[] forwarded = Arrays.copyOfRange(args, 1, args.length);
        if ("--tango".equals(args[0])) {
            PrintTangoConcerts.main(forwarded);
        } else if ("--checkpages".equals(args[0])) {
            CheckWebPagesForChangesWithDiffs.main(forwarded);
        } else if ("--searchjobs".equals(args[0])) {
            SearchJobs.main(forwarded);
        } else {
            printUsage();
            System.exit(1);
        }
    }
}
