// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "teststorageapp.h"
#include <vespa/storage/bucketdb/storagebucketdbinitializer.h>
#include <vespa/storage/config/config-stor-server.h>
#include <vespa/config-stor-distribution.h>
#include <vespa/config-load-type.h>
#include <vespa/config-fleetcontroller.h>
#include <vespa/persistence/dummyimpl/dummypersistence.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/util/time.h>
#include <vespa/config/config.h>
#include <vespa/config/helper/configgetter.hpp>
#include <thread>

#include <vespa/log/log.h>
LOG_SETUP(".test.servicelayerapp");

using storage::framework::defaultimplementation::ComponentRegisterImpl;

namespace storage {

namespace {
    template<typename T>
    struct ConfigReader : public T::Subscriber,
                          public T
    {
        ConfigReader(const std::string& configId) {
            T::subscribe(configId, *this);
        }
        void configure(const T& c) { dynamic_cast<T&>(*this) = c; }
    };
}

TestStorageApp::TestStorageApp(StorageComponentRegisterImpl::UP compReg,
                               const lib::NodeType& type, NodeIndex index,
                               vespalib::stringref configId)
    : TestComponentRegister(ComponentRegisterImpl::UP(std::move(compReg))),
      _compReg(dynamic_cast<StorageComponentRegisterImpl&>(TestComponentRegister::getComponentRegister())),
      _docMan(),
      _nodeStateUpdater(type),
      _configId(configId),
      _initialized(false)
{
        // Use config to adjust values
    vespalib::string clusterName = "mycluster";
    uint32_t redundancy = 2;
    uint32_t nodeCount = 10;
    documentapi::LoadTypeSet::SP loadTypes;
    if (!configId.empty()) {
        config::ConfigUri uri(configId);
        std::unique_ptr<vespa::config::content::core::StorServerConfig> serverConfig = config::ConfigGetter<vespa::config::content::core::StorServerConfig>::getConfig(uri.getConfigId(), uri.getContext());
        clusterName = serverConfig->clusterName;
        if (index == 0xffff) index = serverConfig->nodeIndex;
        redundancy = config::ConfigGetter<vespa::config::content::StorDistributionConfig>::getConfig(uri.getConfigId(), uri.getContext())->redundancy;
        nodeCount = config::ConfigGetter<vespa::config::content::FleetcontrollerConfig>::getConfig(uri.getConfigId(), uri.getContext())->totalStorageCount;
        _compReg.setPriorityConfig(
                *config::ConfigGetter<StorageComponent::PriorityConfig>
                    ::getConfig(uri.getConfigId(), uri.getContext()));
        loadTypes = std::make_shared<documentapi::LoadTypeSet>(
                *config::ConfigGetter<vespa::config::content::LoadTypeConfig>
                    ::getConfig(uri.getConfigId(), uri.getContext()));
    } else {
        if (index == 0xffff) index = 0;
        loadTypes.reset(new documentapi::LoadTypeSet);
    }
    if (index >= nodeCount) nodeCount = index + 1;
    if (redundancy > nodeCount) redundancy = nodeCount;

    _compReg.setNodeInfo(clusterName, type, index);
    _compReg.setNodeStateUpdater(_nodeStateUpdater);
    _compReg.setDocumentTypeRepo(_docMan.getTypeRepoSP());
    _compReg.setLoadTypes(loadTypes);
    _compReg.setBucketIdFactory(document::BucketIdFactory());
    auto distr = std::make_shared<lib::Distribution>(
            lib::Distribution::getDefaultDistributionConfig(redundancy, nodeCount));
    _compReg.setDistribution(distr);
}

TestStorageApp::~TestStorageApp() = default;

void
TestStorageApp::setDistribution(Redundancy redundancy, NodeCount nodeCount)
{
    auto distr = std::make_shared<lib::Distribution>(
            lib::Distribution::getDefaultDistributionConfig(redundancy, nodeCount));
    _compReg.setDistribution(distr);
}

void
TestStorageApp::setTypeRepo(std::shared_ptr<const document::DocumentTypeRepo> repo)
{
    _compReg.setDocumentTypeRepo(repo);
}

void
TestStorageApp::setClusterState(const lib::ClusterState& c)
{
    _nodeStateUpdater.setClusterState(std::make_shared<lib::ClusterState>(c));
}

void
TestStorageApp::waitUntilInitialized(
        StorageBucketDBInitializer* initializer, framework::SecondTime timeout)
{
        // Always use real clock for wait timeouts. Component clock may be faked
        // in tests
    framework::defaultimplementation::RealClock clock;
    framework::MilliSecTime endTime(clock.getTimeInMillis() + timeout.getMillis());
    while (!isInitialized()) {
        std::this_thread::sleep_for(1ms);
        framework::MilliSecTime currentTime(clock.getTimeInMillis());
        if (currentTime > endTime) {
            std::ostringstream error;
            error << "Failed to initialize service layer within timeout of "
                  << timeout << " seconds.";
            if (initializer != 0) {
                error << " ";
                initializer->reportStatus(error, framework::HttpUrlPath(""));
                LOG(error, "%s", error.str().c_str());
                throw std::runtime_error(error.str());
            }
        }
    }
}

namespace {
    NodeIndex getIndexFromConfig(vespalib::stringref configId) {
        if (!configId.empty()) {
            config::ConfigUri uri(configId);
            return NodeIndex(
                config::ConfigGetter<vespa::config::content::core::StorServerConfig>::getConfig(uri.getConfigId(), uri.getContext())->nodeIndex);
        }
        return NodeIndex(0);
    }
}

TestServiceLayerApp::TestServiceLayerApp(vespalib::stringref configId)
    : TestStorageApp(std::make_unique<ServiceLayerComponentRegisterImpl>(true), // TODO remove B-tree flag once default
                     lib::NodeType::STORAGE, getIndexFromConfig(configId), configId),
      _compReg(dynamic_cast<ServiceLayerComponentRegisterImpl&>(TestStorageApp::getComponentRegister())),
      _persistenceProvider()
{
    _compReg.setDiskCount(1);
    lib::NodeState ns(*_nodeStateUpdater.getReportedNodeState());
    ns.setDiskCount(1);
    _nodeStateUpdater.setReportedNodeState(ns);
}

TestServiceLayerApp::TestServiceLayerApp(NodeIndex index,
                                         vespalib::stringref configId)
    : TestStorageApp(std::make_unique<ServiceLayerComponentRegisterImpl>(true), // TODO remove B-tree flag once default
                     lib::NodeType::STORAGE, index, configId),
      _compReg(dynamic_cast<ServiceLayerComponentRegisterImpl&>(TestStorageApp::getComponentRegister())),
      _persistenceProvider()
{
    _compReg.setDiskCount(1);
    lib::NodeState ns(*_nodeStateUpdater.getReportedNodeState());
    ns.setDiskCount(1);
    _nodeStateUpdater.setReportedNodeState(ns);
}

TestServiceLayerApp::~TestServiceLayerApp() = default;

void
TestServiceLayerApp::setupDummyPersistence()
{
    assert(_compReg.getDiskCount() == 1u);
    auto provider = std::make_unique<spi::dummy::DummyPersistence>(getTypeRepo());
    provider->initialize();
    setPersistenceProvider(std::move(provider));
}

void
TestServiceLayerApp::setPersistenceProvider(PersistenceProviderUP provider)
{
    _persistenceProvider = std::move(provider);
}

spi::PersistenceProvider&
TestServiceLayerApp::getPersistenceProvider()
{
    if (_persistenceProvider.get() == 0) {
        throw vespalib::IllegalStateException("Persistence provider requested but not initialized.", VESPA_STRLOC);
    }
    return *_persistenceProvider;
}

namespace {
    template<typename T>
    const T getConfig(vespalib::stringref configId) {
        config::ConfigUri uri(configId);
        return *config::ConfigGetter<T>::getConfig(uri.getConfigId(), uri.getContext());
    }
}

void
TestDistributorApp::configure(vespalib::stringref id)
{
    if (id.empty()) return;
    DistributorConfig dc(getConfig<vespa::config::content::core::StorDistributormanagerConfig>(id));
    _compReg.setDistributorConfig(dc);
    VisitorConfig vc(getConfig<vespa::config::content::core::StorVisitordispatcherConfig>(id));
    _compReg.setVisitorConfig(vc);
}

TestDistributorApp::TestDistributorApp(vespalib::stringref configId)
    : TestStorageApp(
            std::make_unique<DistributorComponentRegisterImpl>(),
            lib::NodeType::DISTRIBUTOR, getIndexFromConfig(configId), configId),
      _compReg(dynamic_cast<DistributorComponentRegisterImpl&>(TestStorageApp::getComponentRegister())),
      _lastUniqueTimestampRequested(0),
      _uniqueTimestampCounter(0)
{
    _compReg.setTimeCalculator(*this);
    configure(configId);
}

TestDistributorApp::TestDistributorApp(NodeIndex index, vespalib::stringref configId)
    : TestStorageApp(
            std::make_unique<DistributorComponentRegisterImpl>(),
            lib::NodeType::DISTRIBUTOR, index, configId),
      _compReg(dynamic_cast<DistributorComponentRegisterImpl&>(TestStorageApp::getComponentRegister())),
      _lastUniqueTimestampRequested(0),
      _uniqueTimestampCounter(0)
{
    _compReg.setTimeCalculator(*this);
    configure(configId);
}

TestDistributorApp::~TestDistributorApp() = default;

api::Timestamp
TestDistributorApp::getUniqueTimestamp()
{
    std::lock_guard guard(_accessLock);
    uint64_t timeNow(getClock().getTimeInSeconds().getTime());
    if (timeNow == _lastUniqueTimestampRequested) {
        ++_uniqueTimestampCounter;
    } else {
        if (timeNow < _lastUniqueTimestampRequested) {
            LOG(error, "Time has moved backwards, from %" PRIu64 " to %" PRIu64 ".",
                    _lastUniqueTimestampRequested, timeNow);
        }
        _lastUniqueTimestampRequested = timeNow;
        _uniqueTimestampCounter = 0;
    }

    return _lastUniqueTimestampRequested * 1000000ll + _uniqueTimestampCounter;
}

} // storage
