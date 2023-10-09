// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.v2.response;

import com.yahoo.config.provision.TenantName;
import com.yahoo.restapi.MessageResponse;

/**
 * Response for tenant create
 *
 * @author hmusum
 */
public class TenantGetResponse extends MessageResponse {

    public TenantGetResponse(TenantName tenant) {
        super("Tenant '" + tenant + "' exists.");
    }

}
