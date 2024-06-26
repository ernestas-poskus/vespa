// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.builder;

import com.yahoo.config.model.ApplicationConfigProducerRoot;
import com.yahoo.config.model.ConfigModelRepo;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.producer.AnyConfigProducer;
import com.yahoo.config.model.producer.TreeConfigProducer;

/**
 * Base class for classes capable of building vespa model.
 *
 * @author Vegard Havdal
 */
public abstract class VespaModelBuilder {

    public abstract ApplicationConfigProducerRoot getRoot(String name, DeployState deployState, TreeConfigProducer<AnyConfigProducer> parent);

    /**
     * Processing that requires access across plugins
     *
     * @param producerRoot the root producer.
     * @param configModelRepo a {@link com.yahoo.config.model.ConfigModelRepo instance}
     */
    public abstract void postProc(DeployState deployState, TreeConfigProducer<AnyConfigProducer> producerRoot, ConfigModelRepo configModelRepo);

}
