// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeType;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.applicationmodel.ServiceInstance;
import com.yahoo.vespa.applicationmodel.ServiceStatus;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.hosted.provision.provisioning.DockerHostCapacity;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.vespa.orchestrator.status.HostStatus;
import com.yahoo.vespa.service.monitor.ServiceMonitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author oyving
 */
public class MetricsReporter extends Maintainer {

    private final Metric metric;
    private final Function<HostName, Optional<HostStatus>> orchestrator;
    private final ServiceMonitor serviceMonitor;
    private final Map<Map<String, String>, Metric.Context> contextMap = new HashMap<>();
    private final Supplier<Integer> pendingRedeploymentsSupplier;

    MetricsReporter(NodeRepository nodeRepository,
                    Metric metric,
                    Orchestrator orchestrator,
                    ServiceMonitor serviceMonitor,
                    Supplier<Integer> pendingRedeploymentsSupplier,
                    Duration interval) {
        super(nodeRepository, interval);
        this.metric = metric;
        this.orchestrator = orchestrator.getNodeStatuses();
        this.serviceMonitor = serviceMonitor;
        this.pendingRedeploymentsSupplier = pendingRedeploymentsSupplier;
    }

    @Override
    public void maintain() {
        List<Node> nodes = nodeRepository().getNodes();

        Map<HostName, List<ServiceInstance>> servicesByHost =
                serviceMonitor.getServiceModelSnapshot().getServiceInstancesByHostName();

        nodes.forEach(node -> updateNodeMetrics(node, servicesByHost));
        updateStateMetrics(nodes);
        updateMaintenanceMetrics();
        updateDockerMetrics(nodes);
    }

    private void updateMaintenanceMetrics() {
        metric.set("hostedVespa.pendingRedeployments", pendingRedeploymentsSupplier.get(), null);
    }

    private void updateNodeMetrics(Node node, Map<HostName, List<ServiceInstance>> servicesByHost) {
        Metric.Context context;

        Optional<Allocation> allocation = node.allocation();
        if (allocation.isPresent()) {
            ApplicationId applicationId = allocation.get().owner();
            context = getContextAt(
                    "state", node.state().name(),
                    "host", node.hostname(),
                    "tenantName", applicationId.tenant().value(),
                    "applicationId", applicationId.serializedForm().replace(':', '.'),
                    "app", toApp(applicationId),
                    "clustertype", allocation.get().membership().cluster().type().name(),
                    "clusterid", allocation.get().membership().cluster().id().value());

            long wantedRestartGeneration = allocation.get().restartGeneration().wanted();
            metric.set("wantedRestartGeneration", wantedRestartGeneration, context);
            long currentRestartGeneration = allocation.get().restartGeneration().current();
            metric.set("currentRestartGeneration", currentRestartGeneration, context);
            boolean wantToRestart = currentRestartGeneration < wantedRestartGeneration;
            metric.set("wantToRestart", wantToRestart ? 1 : 0, context);

            Version wantedVersion = allocation.get().membership().cluster().vespaVersion();
            double wantedVersionNumber = getVersionAsNumber(wantedVersion);
            metric.set("wantedVespaVersion", wantedVersionNumber, context);

            Optional<Version> currentVersion = node.status().vespaVersion();
            boolean converged = currentVersion.isPresent() &&
                    currentVersion.get().equals(wantedVersion);
            metric.set("wantToChangeVespaVersion", converged ? 0 : 1, context);
        } else {
            context = getContextAt(
                    "state", node.state().name(),
                    "host", node.hostname());
        }

        Optional<Version> currentVersion = node.status().vespaVersion();
        if (currentVersion.isPresent()) {
            double currentVersionNumber = getVersionAsNumber(currentVersion.get());
            metric.set("currentVespaVersion", currentVersionNumber, context);
        }

        long wantedRebootGeneration = node.status().reboot().wanted();
        metric.set("wantedRebootGeneration", wantedRebootGeneration, context);
        long currentRebootGeneration = node.status().reboot().current();
        metric.set("currentRebootGeneration", currentRebootGeneration, context);
        boolean wantToReboot = currentRebootGeneration < wantedRebootGeneration;
        metric.set("wantToReboot", wantToReboot ? 1 : 0, context);

        metric.set("wantToRetire", node.status().wantToRetire() ? 1 : 0, context);
        metric.set("wantToDeprovision", node.status().wantToDeprovision() ? 1 : 0, context);
        metric.set("hardwareFailure",
                node.status().hardwareFailureDescription().isPresent() ? 1 : 0,
                context);
        metric.set("hardwareDivergence",
                node.status().hardwareDivergence().isPresent() ? 1 : 0,
                context);

        orchestrator.apply(new HostName(node.hostname()))
                    .map(status -> status == HostStatus.ALLOWED_TO_BE_DOWN ? 1 : 0)
                    .ifPresent(allowedToBeDown -> metric.set("allowedToBeDown", allowedToBeDown, context));

        long numberOfServices;
        HostName hostName = new HostName(node.hostname());
        List<ServiceInstance> services = servicesByHost.get(hostName);
        if (services == null) {
            numberOfServices = 0;
        } else {
            Map<ServiceStatus, Long> servicesCount = services.stream().collect(
                    Collectors.groupingBy(ServiceInstance::serviceStatus, Collectors.counting()));

            numberOfServices = servicesCount.values().stream().mapToLong(Long::longValue).sum();

            metric.set(
                    "numberOfServicesUp",
                    servicesCount.getOrDefault(ServiceStatus.UP, 0L),
                    context);

            metric.set(
                    "numberOfServicesNotChecked",
                    servicesCount.getOrDefault(ServiceStatus.NOT_CHECKED, 0L),
                    context);

            long numberOfServicesDown = servicesCount.getOrDefault(ServiceStatus.DOWN, 0L);
            metric.set("numberOfServicesDown", numberOfServicesDown, context);

            metric.set("someServicesDown", (numberOfServicesDown > 0 ? 1 : 0), context);

            boolean badNode = NodeFailer.badNode(services);
            metric.set("nodeFailerBadNode", (badNode ? 1 : 0), context);

            boolean nodeDownInNodeRepo = node.history().event(History.Event.Type.down).isPresent();
            metric.set("downInNodeRepo", (nodeDownInNodeRepo ? 1 : 0), context);
        }

        metric.set("numberOfServices", numberOfServices, context);
    }

    private static String toApp(ApplicationId applicationId) {
        return applicationId.application().value() + "." + applicationId.instance().value();
    }

    /**
     * A version 6.163.20 will be returned as a number 163.020. The major
     * version can normally be inferred. As long as the micro version stays
     * below 1000 these numbers sort like Version.
     */
    private static double getVersionAsNumber(Version version) {
        return version.getMinor() + version.getMicro() / 1000.0;
    }

    private Metric.Context getContextAt(String... point) {
        if (point.length % 2 != 0) {
            throw new IllegalArgumentException("Dimension specification comes in pairs");
        }

        Map<String, String> dimensions = new HashMap<>();
        for (int i = 0; i < point.length; i += 2) {
            dimensions.put(point[i], point[i + 1]);
        }

        Metric.Context context = contextMap.get(dimensions);
        if (context != null) {
            return context;
        }

        context = metric.createContext(dimensions);
        contextMap.put(dimensions, context);
        return context;
    }

    private void updateStateMetrics(List<Node> nodes) {
        Map<Node.State, List<Node>> nodesByState = nodes.stream()
                .collect(Collectors.groupingBy(Node::state));

        // Metrics pr state
        for (Node.State state : Node.State.values()) {
            List<Node> nodesInState = nodesByState.getOrDefault(state, new ArrayList<>());
            long size = nodesInState.stream().filter(node -> node.type() == NodeType.tenant).count();
            metric.set("hostedVespa." + state.name() + "Hosts", size, null);
        }
    }

    private void updateDockerMetrics(List<Node> nodes) {
        // Capacity flavors for docker
        DockerHostCapacity capacity = new DockerHostCapacity(nodes);
        metric.set("hostedVespa.docker.totalCapacityCpu", capacity.getCapacityTotal().vcpu(), null);
        metric.set("hostedVespa.docker.totalCapacityMem", capacity.getCapacityTotal().memoryGb(), null);
        metric.set("hostedVespa.docker.totalCapacityDisk", capacity.getCapacityTotal().diskGb(), null);
        metric.set("hostedVespa.docker.freeCapacityCpu", capacity.getFreeCapacityTotal().vcpu(), null);
        metric.set("hostedVespa.docker.freeCapacityMem", capacity.getFreeCapacityTotal().memoryGb(), null);
        metric.set("hostedVespa.docker.freeCapacityDisk", capacity.getFreeCapacityTotal().diskGb(), null);

        List<Flavor> dockerFlavors = nodeRepository().getAvailableFlavors().getFlavors().stream()
                .filter(f -> f.getType().equals(Flavor.Type.DOCKER_CONTAINER))
                .collect(Collectors.toList());
        for (Flavor flavor : dockerFlavors) {
            Metric.Context context = getContextAt("flavor", flavor.name());
            metric.set("hostedVespa.docker.freeCapacityFlavor", capacity.freeCapacityInFlavorEquivalence(flavor), context);
            metric.set("hostedVespa.docker.hostsAvailableFlavor", capacity.getNofHostsAvailableFor(flavor), context);
        }


    }
}
