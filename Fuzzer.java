import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Fuzzer {
    private static class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '%s'.".formatted(commandToFuzz));
        }

        String seedInput = "<html a=\"value\">...</html>";

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        runCommand(builder, seedInput, getMutatedInputs(seedInput, List.of(
                input -> input.replace("<html", "a"), // this is just a placeholder, mutators should not only do hard-coded string replacement
                input -> input.replace("<html", "")
        )));
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private static void runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).forEach(input -> {
            try {
                Process process = builder.start();

                System.out.printf("Input: %s\n", input);
                try (OutputStream streamToCommand = process.getOutputStream()) {
                    streamToCommand.write(input.getBytes());
                    streamToCommand.flush();
                }

                int exitCode = process.waitFor();
                System.out.printf("Exit code: %s\n", exitCode);

                InputStream streamFromCommand = process.getInputStream();
                String output = readStreamIntoString(streamFromCommand);
                streamFromCommand.close();
                System.out.printf("Output: %s\n", output
                        // ignore warnings due to usage of gets() in test program
                        .replaceAll("warning: this program uses gets\\(\\), which is unsafe.", "")
                        .trim()
                );

                if (exitCode != 0) {
                    System.out.println("Non-zero exit code detected!");
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        List<String> mutations = new ArrayList<>();
        for (Function<String, String> mutator : mutators) {
            mutations.add(mutator.apply(seedInput));
        }
        return mutations;
    }
    }
}
