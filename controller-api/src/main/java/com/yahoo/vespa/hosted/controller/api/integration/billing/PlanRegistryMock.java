// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.billing;

import com.yahoo.config.provision.NodeResources;
import com.yahoo.vespa.hosted.controller.api.integration.resource.CostInfo;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class PlanRegistryMock implements PlanRegistry {

    public static final Plan freeTrial = new MockPlan("trial", false, false, 0, 0, 0, 0, 200, "Free Trial - for testing purposes");
    public static final Plan paidPlan  = new MockPlan("paid", true, true, "0.09", "0.009", "0.0003", "0.075", 500, "Paid Plan - for testing purposes");
    public static final Plan nonePlan  = new MockPlan("none", false, false, 0, 0, 0, 0, 0, "None Plan - for testing purposes");

    @Override
    public Plan defaultPlan() {
        return freeTrial;
    }

    @Override
    public Optional<Plan> plan(PlanId planId) {
        return Stream.of(freeTrial, paidPlan, nonePlan)
                .filter(p -> p.id().equals(planId))
                .findAny();
    }

    @Override
    public List<Plan> all() {
        return List.of(freeTrial, paidPlan, nonePlan);
    }

    private static class MockPlan implements Plan {
        private final PlanId planId;
        private final String description;
        private final CostCalculator costCalculator;
        private final QuotaCalculator quotaCalculator;
        private final boolean billed;
        private final boolean supported;

        public MockPlan(String planId, boolean billed, boolean supported, double cpuPrice, double memPrice, double dgbPrice, double gpuPrice, int quota, String description) {
            this(PlanId.from(planId), billed, supported, new MockCostCalculator(cpuPrice, memPrice, dgbPrice, gpuPrice), () -> createQuota(quota), description);
        }

        public MockPlan(String planId, boolean billed, boolean supported, String cpuPrice, String memPrice, String dgbPrice, String gpuPrice, int quota, String description) {
            this(PlanId.from(planId), billed, supported, new MockCostCalculator(cpuPrice, memPrice, dgbPrice, gpuPrice), () -> createQuota(quota), description);
        }

        private static Quota createQuota(int quota) {
            return quota == 0 ? Quota.zero() : Quota.unlimited().withBudget(quota);
        }
        
        public MockPlan(PlanId planId, boolean billed, boolean supported, MockCostCalculator calculator, QuotaCalculator quota, String description) {
            this.planId = planId;
            this.billed = billed;
            this.supported = supported;
            this.costCalculator = calculator;
            this.quotaCalculator = quota;
            this.description = description;
        }

        @Override
        public PlanId id() {
            return planId;
        }

        @Override
        public String displayName() {
            return description;
        }

        @Override
        public CostCalculator calculator() {
            return costCalculator;
        }

        @Override
        public QuotaCalculator quota() {
            return quotaCalculator;
        }

        @Override
        public boolean isBilled() {
            return billed;
        }

        @Override
        public boolean isSupported() {
            return supported;
        }
    }

    private static class MockCostCalculator implements CostCalculator {
        private static final BigDecimal millisPerHour = BigDecimal.valueOf(60 * 60 * 1000);
        private final BigDecimal cpuHourCost;
        private final BigDecimal memHourCost;
        private final BigDecimal dgbHourCost;
        private final BigDecimal gpuHourCost;

        public MockCostCalculator(String cpuPrice, String memPrice, String dgbPrice, String gpuPrice) {
            this(new BigDecimal(cpuPrice), new BigDecimal(memPrice), new BigDecimal(dgbPrice), new BigDecimal(gpuPrice));
        }

        public MockCostCalculator(double cpuPrice, double memPrice, double dgbPrice, double gpuPrice) {
            this(BigDecimal.valueOf(cpuPrice), BigDecimal.valueOf(memPrice), BigDecimal.valueOf(dgbPrice), BigDecimal.valueOf(gpuPrice));
        }

        public MockCostCalculator(BigDecimal cpuPrice, BigDecimal memPrice, BigDecimal dgbPrice, BigDecimal gpuPrice) {
            this.cpuHourCost = cpuPrice;
            this.memHourCost = memPrice;
            this.dgbHourCost = dgbPrice;
            this.gpuHourCost = gpuPrice;
        }

        @Override
        public CostInfo calculate(ResourceUsage usage) {
            var cpuCost = usage.getCpuMillis().multiply(cpuHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            var memCost = usage.getMemoryMillis().multiply(memHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            var dgbCost = usage.getDiskMillis().multiply(dgbHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            var gpuCost = usage.getGpuMillis().multiply(gpuHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);

            return new CostInfo(
                    usage.getApplicationId(),
                    usage.getZoneId(),
                    usage.getCpuMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    usage.getMemoryMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    usage.getDiskMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    usage.getGpuMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    cpuCost,
                    memCost,
                    dgbCost,
                    gpuCost,
                    usage.getArchitecture(),
                    usage.getMajorVersion()
            );
        }

        @Override
        public double calculate(NodeResources resources) {
            return resources.cost();
        }
    }
}
