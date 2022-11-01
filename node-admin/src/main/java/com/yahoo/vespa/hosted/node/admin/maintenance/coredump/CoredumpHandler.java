// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.coredump;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.security.SecretSharedKey;
import com.yahoo.security.SharedKeyGenerator;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.container.metrics.Dimensions;
import com.yahoo.vespa.hosted.node.admin.container.metrics.Metrics;
import com.yahoo.vespa.hosted.node.admin.maintenance.sync.ZstdCompressingInputStream;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.ConvergenceException;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder;
import com.yahoo.vespa.hosted.node.admin.task.util.file.UnixPath;
import com.yahoo.vespa.hosted.node.admin.task.util.fs.ContainerPath;
import com.yahoo.vespa.hosted.node.admin.task.util.process.Terminal;

import javax.crypto.CipherOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameEndsWith;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameMatches;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameStartsWith;
import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * Finds coredumps, collects metadata and reports them
 *
 * @author freva
 */
public class CoredumpHandler {

    private static final Pattern HS_ERR_PATTERN = Pattern.compile("hs_err_pid[0-9]+\\.log");
    private static final String PROCESSING_DIRECTORY_NAME = "processing";
    private static final String METADATA_FILE_NAME = "metadata.json";
    private static final String COMPRESSED_EXTENSION = ".zst";
    private static final String ENCRYPTED_EXTENSION = ".enc";
    public static final String COREDUMP_FILENAME_PREFIX = "dump_";

    private final Logger logger = Logger.getLogger(CoredumpHandler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CoreCollector coreCollector;
    private final CoredumpReporter coredumpReporter;
    private final String crashPatchInContainer;
    private final Path doneCoredumpsPath;
    private final Metrics metrics;
    private final Clock clock;
    private final Supplier<String> coredumpIdSupplier;
    private final Supplier<SecretSharedKey> secretSharedKeySupplier;

    /**
     * @param crashPathInContainer path inside the container where core dump are dumped
     * @param doneCoredumpsPath path on host where processed core dumps are stored
     */
    public CoredumpHandler(Terminal terminal, CoreCollector coreCollector, CoredumpReporter coredumpReporter,
                           String crashPathInContainer, Path doneCoredumpsPath, Metrics metrics) {
        this(coreCollector, coredumpReporter, crashPathInContainer, doneCoredumpsPath,
                metrics, Clock.systemUTC(), () -> UUID.randomUUID().toString(), () -> null /*TODO*/);
    }

    CoredumpHandler(CoreCollector coreCollector, CoredumpReporter coredumpReporter,
                    String crashPathInContainer, Path doneCoredumpsPath, Metrics metrics,
                    Clock clock, Supplier<String> coredumpIdSupplier,
                    Supplier<SecretSharedKey> secretSharedKeySupplier) {
        this.coreCollector = coreCollector;
        this.coredumpReporter = coredumpReporter;
        this.crashPatchInContainer = crashPathInContainer;
        this.doneCoredumpsPath = doneCoredumpsPath;
        this.metrics = metrics;
        this.clock = clock;
        this.coredumpIdSupplier = coredumpIdSupplier;
        this.secretSharedKeySupplier = secretSharedKeySupplier;
    }


    public void converge(NodeAgentContext context, Supplier<Map<String, Object>> nodeAttributesSupplier, boolean throwIfCoreBeingWritten) {
        ContainerPath containerCrashPath = context.paths().of(crashPatchInContainer, context.users().vespa());
        ContainerPath containerProcessingPath = containerCrashPath.resolve(PROCESSING_DIRECTORY_NAME);

        updateMetrics(context, containerCrashPath);

        if (throwIfCoreBeingWritten) {
            List<String> pendingCores = FileFinder.files(containerCrashPath)
                    .match(fileAttributes -> !isReadyForProcessing(fileAttributes))
                    .maxDepth(1).stream()
                    .map(FileFinder.FileAttributes::filename)
                    .toList();
            if (!pendingCores.isEmpty())
                throw ConvergenceException.ofError(String.format("Cannot process %s coredumps: Still being written",
                        pendingCores.size() < 5 ? pendingCores : pendingCores.size()));
        }

        // Check if we have already started to process a core dump or we can enqueue a new core one
        getCoredumpToProcess(containerCrashPath, containerProcessingPath)
                .ifPresent(path -> processAndReportSingleCoredump(context, path, nodeAttributesSupplier));
    }

    /** @return path to directory inside processing directory that contains a core dump file to process */
    Optional<ContainerPath> getCoredumpToProcess(ContainerPath containerCrashPath, ContainerPath containerProcessingPath) {
        return FileFinder.directories(containerProcessingPath).stream()
                .map(FileFinder.FileAttributes::path)
                .findAny()
                .map(ContainerPath.class::cast)
                .or(() -> enqueueCoredump(containerCrashPath, containerProcessingPath));
    }

    /**
     * Moves a coredump and related hs_err file(s) to a new directory under the processing/ directory.
     * Limit to only processing one coredump at the time, starting with the oldest.
     *
     * Assumption: hs_err files are much smaller than core files and are written (last modified time)
     * before the core file.
     *
     * @return path to directory inside processing directory which contains the enqueued core dump file
     */
    Optional<ContainerPath> enqueueCoredump(ContainerPath containerCrashPath, ContainerPath containerProcessingPath) {
        List<Path> toProcess = FileFinder.files(containerCrashPath)
                .match(this::isReadyForProcessing)
                .maxDepth(1)
                .stream()
                .sorted(Comparator.comparing(FileFinder.FileAttributes::lastModifiedTime))
                .map(FileFinder.FileAttributes::path)
                .toList();

        int coredumpIndex = IntStream.range(0, toProcess.size())
                .filter(i -> !HS_ERR_PATTERN.matcher(toProcess.get(i).getFileName().toString()).matches())
                .findFirst()
                .orElse(-1);

        // Either there are no files in crash directory, or all the files are hs_err files.
        if (coredumpIndex == -1) return Optional.empty();

        ContainerPath enqueuedDir = (ContainerPath) uncheck(() -> Files.createDirectories(containerProcessingPath.resolve(coredumpIdSupplier.get())));
        IntStream.range(0, coredumpIndex + 1)
                .forEach(i -> {
                    Path path = toProcess.get(i);
                    String prefix = i == coredumpIndex ? COREDUMP_FILENAME_PREFIX : "";
                    uncheck(() -> Files.move(path, enqueuedDir.resolve(prefix + path.getFileName())));
                });
        return Optional.of(enqueuedDir);
    }

    void processAndReportSingleCoredump(NodeAgentContext context, ContainerPath coredumpDirectory, Supplier<Map<String, Object>> nodeAttributesSupplier) {
        try {
            Optional<SecretSharedKey> sharedCoreKey = Optional.ofNullable(secretSharedKeySupplier.get());
            Optional<String> decryptionToken = sharedCoreKey.map(k -> k.sealedSharedKey().toTokenString());
            String metadata = getMetadata(context, coredumpDirectory, nodeAttributesSupplier, decryptionToken);
            String coredumpId = coredumpDirectory.getFileName().toString();
            coredumpReporter.reportCoredump(coredumpId, metadata);
            finishProcessing(context, coredumpDirectory, sharedCoreKey);
            context.log(logger, "Successfully reported coredump " + coredumpId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process coredump " + coredumpDirectory, e);
        }
    }

    /**
     * @return coredump metadata from metadata.json if present, otherwise attempts to get metadata using
     * {@link CoreCollector} and stores it to metadata.json
     */
    String getMetadata(NodeAgentContext context, ContainerPath coredumpDirectory, Supplier<Map<String, Object>> nodeAttributesSupplier, Optional<String> decryptionToken) throws IOException {
        UnixPath metadataPath = new UnixPath(coredumpDirectory.resolve(METADATA_FILE_NAME));
        if (!metadataPath.exists()) {
            ContainerPath coredumpFile = findCoredumpFileInProcessingDirectory(coredumpDirectory);
            Map<String, Object> metadata = new HashMap<>(coreCollector.collect(context, coredumpFile));
            metadata.putAll(nodeAttributesSupplier.get());
            metadata.put("coredump_path", doneCoredumpsPath
                    .resolve(context.containerName().asString())
                    .resolve(coredumpDirectory.getFileName().toString())
                    .resolve(coredumpFile.getFileName().toString()).toString());
            decryptionToken.ifPresent(token -> metadata.put("decryption_token", token));

            String metadataFields = objectMapper.writeValueAsString(Map.of("fields", metadata));
            metadataPath.writeUtf8File(metadataFields);
            return metadataFields;
        } else {
            if (decryptionToken.isPresent()) {
                // Since encryption keys are single-use and generated for each core dump processing invocation,
                // we must ensure we store and report the token associated with the _latest_ (i.e. current)
                // attempt at processing the core dump. Patch and rewrite the file with a new token, if present.
                String metadataFields = metadataWithPatchedTokenValue(metadataPath, decryptionToken.get());
                metadataPath.deleteIfExists();
                metadataPath.writeUtf8File(metadataFields);
                return metadataFields;
            } else {
                return metadataPath.readUtf8File();
            }
        }
    }

    private String metadataWithPatchedTokenValue(UnixPath metadataPath, String decryptionToken) throws JsonProcessingException {
        var jsonRoot = objectMapper.readTree(metadataPath.readUtf8File());
        if (jsonRoot.path("fields").isObject()) {
            ((ObjectNode)jsonRoot.get("fields")).put("decryption_token", decryptionToken);
        } // else: unit testing case without real metadata
        return objectMapper.writeValueAsString(jsonRoot);
    }

    static OutputStream maybeWrapWithEncryption(OutputStream wrappedStream, Optional<SecretSharedKey> sharedCoreKey) {
        return sharedCoreKey
                .map(key -> (OutputStream)new CipherOutputStream(wrappedStream, SharedKeyGenerator.makeAesGcmEncryptionCipher(key)))
                .orElse(wrappedStream);
    }

    /**
     * Compresses and, if a key is provided, encrypts core file (and deletes the uncompressed core), then moves
     * the entire core dump processing directory to {@link #doneCoredumpsPath} for archive
     */
    private void finishProcessing(NodeAgentContext context, ContainerPath coredumpDirectory, Optional<SecretSharedKey> sharedCoreKey) throws IOException {
        ContainerPath coreFile = findCoredumpFileInProcessingDirectory(coredumpDirectory);
        String extension = COMPRESSED_EXTENSION + (sharedCoreKey.isPresent() ? ENCRYPTED_EXTENSION : "");
        ContainerPath compressedCoreFile = coreFile.resolveSibling(coreFile.getFileName() + extension);

        try (ZstdCompressingInputStream zcis = new ZstdCompressingInputStream(Files.newInputStream(coreFile));
             OutputStream fos = maybeWrapWithEncryption(Files.newOutputStream(compressedCoreFile), sharedCoreKey)) {
            zcis.transferTo(fos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Files.delete(coreFile);

        Path newCoredumpDirectory = doneCoredumpsPath.resolve(context.containerName().asString());
        uncheck(() -> Files.createDirectories(newCoredumpDirectory));
        // Files.move() does not support moving non-empty directories across providers, move using host paths
        Files.move(coredumpDirectory.pathOnHost(), newCoredumpDirectory.resolve(coredumpDirectory.getFileName().toString()));
    }

    ContainerPath findCoredumpFileInProcessingDirectory(ContainerPath coredumpProccessingDirectory) {
        return (ContainerPath) FileFinder.files(coredumpProccessingDirectory)
                .match(nameStartsWith(COREDUMP_FILENAME_PREFIX).and(nameEndsWith(COMPRESSED_EXTENSION).negate())
                                                               .and(nameEndsWith(ENCRYPTED_EXTENSION).negate()))
                .maxDepth(1)
                .stream()
                .map(FileFinder.FileAttributes::path)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No coredump file found in processing directory " + coredumpProccessingDirectory));
    }

    void updateMetrics(NodeAgentContext context, ContainerPath containerCrashPath) {
        Dimensions dimensions = generateDimensions(context);

        // Unprocessed coredumps
        int numberOfUnprocessedCoredumps = FileFinder.files(containerCrashPath)
                .match(nameStartsWith(".").negate())
                .match(nameMatches(HS_ERR_PATTERN).negate())
                .match(nameEndsWith(COMPRESSED_EXTENSION).negate())
                .match(nameEndsWith(ENCRYPTED_EXTENSION).negate())
                .match(nameStartsWith("metadata").negate())
                .list().size();

        metrics.declareGauge(Metrics.APPLICATION_NODE, "coredumps.enqueued", dimensions, Metrics.DimensionType.PRETAGGED).sample(numberOfUnprocessedCoredumps);

        // Processed coredumps
        Path processedCoredumpsPath = doneCoredumpsPath.resolve(context.containerName().asString());
        int numberOfProcessedCoredumps = FileFinder.directories(processedCoredumpsPath)
                .maxDepth(1)
                .list().size();

        metrics.declareGauge(Metrics.APPLICATION_NODE, "coredumps.processed", dimensions, Metrics.DimensionType.PRETAGGED).sample(numberOfProcessedCoredumps);
    }

    private Dimensions generateDimensions(NodeAgentContext context) {
        NodeSpec node = context.node();
        Dimensions.Builder dimensionsBuilder = new Dimensions.Builder()
                .add("host", node.hostname())
                .add("flavor", node.flavor())
                .add("state", node.state().toString())
                .add("zone", context.zone().getId().value());

        node.owner().ifPresent(owner ->
            dimensionsBuilder
                    .add("tenantName", owner.tenant().value())
                    .add("applicationName", owner.application().value())
                    .add("instanceName", owner.instance().value())
                    .add("app", String.join(".", owner.application().value(), owner.instance().value()))
                    .add("applicationId", owner.toFullString())
        );

        node.membership().ifPresent(membership ->
            dimensionsBuilder
                    .add("clustertype", membership.type().value())
                    .add("clusterid", membership.clusterId())
        );

        node.parentHostname().ifPresent(parent -> dimensionsBuilder.add("parentHostname", parent));
        dimensionsBuilder.add("orchestratorState", node.orchestratorStatus().asString());
        dimensionsBuilder.add("system", context.zone().getSystemName().value());

        return dimensionsBuilder.build();
    }

    private boolean isReadyForProcessing(FileFinder.FileAttributes fileAttributes) {
        // Wait at least a minute until we start processing a core/heap dump to ensure that
        // kernel/JVM has finished writing it
        return clock.instant().minusSeconds(60).isAfter(fileAttributes.lastModifiedTime());
    }

}
