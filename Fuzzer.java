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

    private static String insertRandomChar(String input, Random random) {
        char randomChar = (char) (random.nextInt(95) + 32); // printable ASCII chars
        int position = random.nextInt(input.length() + 1);
        return input.substring(0, position) + randomChar + input.substring(position);
    }

    private static String insertExistingChar(String input, Random random) {
        if (input.isEmpty()) return input;
        char existingChar = input.charAt(random.nextInt(input.length()));
        int position = random.nextInt(input.length() + 1);
        return input.substring(0, position) + existingChar + input.substring(position);
    }

    private static String repeatChar(String input, Random random) {
        if (input.isEmpty()) return input;
        int position = random.nextInt(input.length());
        char charToRepeat = input.charAt(position);
        return input.substring(0, position + 1) + charToRepeat + input.substring(position + 1);
    }

    private static String deleteChar(String input, Random random) {
        if (input.isEmpty()) return input;
        int position = random.nextInt(input.length());
        return input.substring(0, position) + input.substring(position + 1);
    }

    private static String swapChars(String input, Random random) {
        if (input.length() < 2) return input;
        int pos1 = random.nextInt(input.length());
        int pos2 = random.nextInt(input.length());
        while (pos2 == pos1) {
            pos2 = random.nextInt(input.length());
        }
        char[] chars = input.toCharArray();
        char temp = chars[pos1];
        chars[pos1] = chars[pos2];
        chars[pos2] = temp;
        return new String(chars);
    }

    private static String switchCase(String input, Random random) {
        if (input.isEmpty()) return input;
        int position = random.nextInt(input.length());
        char[] chars = input.toCharArray();
        chars[position] = Character.isUpperCase(chars[position]) ? 
                Character.toLowerCase(chars[position]) : 
                Character.toUpperCase(chars[position]);
        return new String(chars);
    }

    private static String flipBit(String input, Random random) {
        if (input.isEmpty()) return input;
        int position = random.nextInt(input.length());
        char[] chars = input.toCharArray();
        int bitPosition = random.nextInt(8);
        chars[position] = (char) (chars[position] ^ (1 << bitPosition));
        return new String(chars);
    }

    private static String insertMalformedTag(String input, Random random) {
        String[] malformedTags = {
            "<>", "< >", "</>", "<///>", 
            "<tag", "tag>", 
            "<<tag>>", 
            "<tag attr=>", 
            "<tag attr=\">",
            "<tag attr=\" value>"
        };
        String tagToInsert = malformedTags[random.nextInt(malformedTags.length)];
        int position = random.nextInt(input.length() + 1);
        return input.substring(0, position) + tagToInsert + input.substring(position);
    }
}
