package com.mirth.connect.plugins.datatypes.hl7v2;

import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import com.mirth.connect.model.datatype.SerializerProperties;

/**
 * Characterization of the strict (HAPI) HL7 v2.x paths.
 *
 * <p>
 * This suite does not assert that the engine is correct. It asserts that the engine still does what
 * it did before, which is the only question that matters to a channel already running in
 * production. Every case is recorded as a golden file against the HAPI version currently on the
 * branch; a later HAPI upgrade that changes any recorded outcome fails here with the before and
 * after side by side. A change that looks like an improvement still fails, deliberately, because
 * somebody's transformer is reading the old shape.
 *
 * <p>
 * Only the strict paths are covered, because those are the only ones that enter HAPI:
 * {@code ER7Serializer} gates every parse and encode call on {@code isUseStrictParser()}. The
 * lenient parser is the engine's own code and is covered elsewhere.
 *
 * <p>
 * To record goldens (do this on the OLD library, then upgrade and run normally):
 *
 * <pre>
 * ./gradlew :server:test --tests '*Hl7v2CharacterizationTest*' -Dhl7char.record=true
 * </pre>
 *
 * Recorded outcomes include thrown exceptions. A message that used to be rejected and is now
 * accepted is as much of a behaviour change as one that used to parse and now does not.
 */
public class Hl7v2CharacterizationTest {

    private static final File CASE_DIR = new File("tests/hl7-characterization/cases");
    private static final File GOLDEN_DIR = new File("tests/hl7-characterization/golden");

    private static final boolean RECORD = Boolean.getBoolean("hl7char.record");

    @Test
    public void strictPathsMatchRecordedBehaviour() throws Exception {
        File[] cases = CASE_DIR.listFiles();
        if (cases == null || cases.length == 0) {
            fail("No cases found in " + CASE_DIR.getAbsolutePath());
        }
        Arrays.sort(cases);

        List<String> drift = new ArrayList<String>();
        int compared = 0;

        for (File caseFile : cases) {
            String name = caseFile.getName();
            String input = FileUtils.readFileToString(caseFile, StandardCharsets.UTF_8);

            for (Invocation invocation : invocationsFor(name)) {
                String actual = invocation.run(input);
                File golden = new File(GOLDEN_DIR, name + "." + invocation.label + ".txt");

                if (RECORD) {
                    FileUtils.writeStringToFile(golden, actual, StandardCharsets.UTF_8);
                    continue;
                }

                if (!golden.exists()) {
                    drift.add(name + " [" + invocation.label + "]: no golden recorded. Run with -Dhl7char.record=true on the old library first.");
                    continue;
                }

                compared++;
                String expected = FileUtils.readFileToString(golden, StandardCharsets.UTF_8);
                if (!expected.equals(actual)) {
                    drift.add(name + " [" + invocation.label + "]\n"
                            + "      was: " + oneLine(expected) + "\n"
                            + "      now: " + oneLine(actual));
                }
            }
        }

        if (RECORD) {
            fail("Goldens recorded to " + GOLDEN_DIR.getAbsolutePath() + ". Re-run without -Dhl7char.record to compare.");
        }

        if (!drift.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append(drift.size()).append(" HL7 behaviour change(s) against the recorded baseline");
            message.append(" (").append(compared).append(" outcomes compared).\n");
            message.append("Each of these is a channel in production that may now behave differently.\n\n");
            for (String d : drift) {
                message.append("  ").append(d).append("\n");
            }
            fail(message.toString());
        }
    }

    /** The strict paths reachable for a given input, by file type. */
    private List<Invocation> invocationsFor(String name) {
        List<Invocation> invocations = new ArrayList<Invocation>();

        if (name.endsWith(".er7")) {
            // ER7 in: the pipe parser runs, then the XML parser re-encodes.
            invocations.add(new Invocation("toXML.novalidation") {
                String invoke(String source) throws Exception {
                    return serializer(false).toXML(source);
                }
            });
            invocations.add(new Invocation("toXML.validation") {
                String invoke(String source) throws Exception {
                    return serializer(true).toXML(source);
                }
            });
            // Terser reads that populate the message browser's metadata columns.
            invocations.add(new Invocation("metadata") {
                String invoke(String source) throws Exception {
                    Map<String, Object> map = new TreeMap<String, Object>(serializer(false).getMetaDataFromMessage(source));
                    return String.valueOf(map);
                }
            });
        } else if (name.endsWith(".xml")) {
            // XML in on the outbound path: XML parser, then pipe parser encodes.
            invocations.add(new Invocation("fromXML.novalidation") {
                String invoke(String source) throws Exception {
                    return serializer(false).fromXML(source);
                }
            });
            invocations.add(new Invocation("fromXML.validation") {
                String invoke(String source) throws Exception {
                    return serializer(true).fromXML(source);
                }
            });
            /*
             * XML into toXML only reaches HAPI when strict validation is also on. With validation
             * off the source is passed through untouched, so there is nothing to characterize.
             */
            invocations.add(new Invocation("toXML.validation") {
                String invoke(String source) throws Exception {
                    return serializer(true).toXML(source);
                }
            });
        }

        return invocations;
    }

    private static ER7Serializer serializer(boolean strictValidation) {
        HL7v2SerializationProperties serialization = new HL7v2SerializationProperties();
        serialization.setUseStrictParser(true);
        serialization.setUseStrictValidation(strictValidation);

        HL7v2DeserializationProperties deserialization = new HL7v2DeserializationProperties();
        deserialization.setUseStrictParser(true);
        deserialization.setUseStrictValidation(strictValidation);

        return new ER7Serializer(new SerializerProperties(serialization, deserialization, null));
    }

    /** One strict path, recorded by label. A thrown exception is an outcome, not a test failure. */
    private abstract static class Invocation {
        final String label;

        Invocation(String label) {
            this.label = label;
        }

        abstract String invoke(String source) throws Exception;

        String run(String source) {
            try {
                return invoke(source);
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                return "THREW " + root.getClass().getName() + ": " + normalize(root.getMessage());
            }
        }
    }

    /** Strip anything machine-specific so a golden is portable between checkouts. */
    private static String normalize(String message) {
        if (message == null) {
            return "(no message)";
        }
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.replace(System.getProperty("user.dir"), "<cwd>");
    }

    private static String oneLine(String value) {
        String flat = value.replace("\r", "\\r").replace("\n", "\\n");
        return flat.length() > 300 ? flat.substring(0, 300) + "... (" + flat.length() + " chars)" : flat;
    }
}
