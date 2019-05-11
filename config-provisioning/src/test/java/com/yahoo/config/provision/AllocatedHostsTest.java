// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

import com.yahoo.component.Version;
import com.yahoo.config.provisioning.FlavorsConfig;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * @author bratseth
 */
public class AllocatedHostsTest {

    @Test
    public void testAllocatedHostsSerialization() throws IOException {
        NodeFlavors configuredFlavors = configuredFlavorsFrom("C/12/45/100", 12, 45, 100, Flavor.Type.BARE_METAL);

        Set<HostSpec> hosts = new LinkedHashSet<>();
        hosts.add(new HostSpec("empty",
                               Optional.empty()));
        hosts.add(new HostSpec("with-aliases",
                               List.of("alias1", "alias2")));
        hosts.add(new HostSpec("allocated",
                               Optional.of(ClusterMembership.from("container/test/0/0", com.yahoo.component.Version.fromString("6.73.1")))));
        hosts.add(new HostSpec("flavor-from-resources",
                               Collections.emptyList(), new Flavor(new NodeResources(0.5, 3.1, 4))));
        hosts.add(new HostSpec("configured-flavor",
                               Collections.emptyList(), configuredFlavors.getFlavorOrThrow("C/12/45/100")));
        hosts.add(new HostSpec("with-version",
                               Collections.emptyList(), Optional.empty(), Optional.empty(), Optional.of(Version.fromString("3.4.5"))));
        hosts.add(new HostSpec("with-ports",
                               Collections.emptyList(), Optional.empty(), Optional.empty(), Optional.empty(),
                               Optional.of(new NetworkPorts(List.of(new NetworkPorts.Allocation(1234, "service1", "configId1", "suffix1"),
                                                                    new NetworkPorts.Allocation(4567, "service2", "configId2", "suffix2"))))));

        assertAllocatedHosts(AllocatedHosts.withHosts(hosts), configuredFlavors);
    }

    private void assertAllocatedHosts(AllocatedHosts expectedHosts, NodeFlavors configuredFlavors) throws IOException {
        AllocatedHosts deserializedHosts = AllocatedHosts.fromJson(expectedHosts.toJson(),
                                                                   Optional.of(configuredFlavors));

        assertEquals(expectedHosts, deserializedHosts);
        for (HostSpec expectedHost : expectedHosts.getHosts()) {
            HostSpec deserializedHost = requireHost(expectedHost.hostname(), deserializedHosts);
            assertEquals(expectedHost.hostname(), deserializedHost.hostname());
            assertEquals(expectedHost.membership(), deserializedHost.membership());
            assertEquals(expectedHost.flavor(), deserializedHost.flavor());
            assertEquals(expectedHost.version(), deserializedHost.version());
            assertEquals(expectedHost.networkPorts(), deserializedHost.networkPorts());
            assertEquals(expectedHost.aliases(), deserializedHost.aliases());
        }
    }

    private HostSpec requireHost(String hostname, AllocatedHosts hosts) {
        for (HostSpec host : hosts.getHosts())
            if (host.hostname().equals(hostname))
                return host;
        throw new IllegalArgumentException("No host " + hostname + " is present");
    }

    public NodeFlavors configuredFlavorsFrom(String flavorName, double cpu, double mem, double disk, Flavor.Type type) {
        FlavorsConfig.Builder b = new FlavorsConfig.Builder();
        FlavorsConfig.Flavor.Builder flavor = new FlavorsConfig.Flavor.Builder();
        flavor.name(flavorName);
        flavor.minDiskAvailableGb(disk);
        flavor.minCpuCores(cpu);
        flavor.minMainMemoryAvailableGb(mem);
        flavor.environment(type.name());
        b.flavor(flavor);
        return new NodeFlavors(b.build());
    }

}
