// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "transport_security_options_reading.h"
#include <vespa/vespalib/data/slime/slime.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/vespalib/io/mapped_file_input.h>
#include <vespa/vespalib/data/memory_input.h>

namespace vespalib::net::tls {

/*

 Proposed JSON format for TLS configuration file:

{
  "files": {
    "private-key": "myhost.key",
    "ca-certificates": "my_cas.pem",
    "certificates": "certs.pem"
  },
  "authorized-peers": [
    {
      "required-credentials":[
        { "field":"CN", "must-match": "*.config.blarg"},
        { "field":"SAN_DNS", "must-match": "*.fancy.config.blarg"}
      ],
      "name": "funky config servers"
    }
  ]
}

 */

using namespace slime::convenience;

namespace {

void verify_referenced_file_exists(const vespalib::string& file_path) {
    if (!fileExists(file_path)) {
        throw IllegalArgumentException(make_string("File '%s' referenced by TLS config does not exist", file_path.c_str()));
    }
}

vespalib::string load_file_referenced_by_field(const Inspector& cursor, const char* field) {
    auto file_path = cursor[field].asString().make_string();
    if (file_path.empty()) {
        throw IllegalArgumentException(make_string("TLS config field '%s' has not been set", field));
    }
    verify_referenced_file_exists(file_path);
    return File::readAll(file_path);
}

RequiredPeerCredential parse_peer_credential(const Inspector& req_entry) {
    auto field_string = req_entry["field"].asString().make_string();
    RequiredPeerCredential::Field field;
    if (field_string == "CN") {
        field = RequiredPeerCredential::Field::CN;
    } else if (field_string == "SAN_DNS") {
        field = RequiredPeerCredential::Field::SAN_DNS;
    } else {
        throw IllegalArgumentException(make_string(
                "Unsupported credential field type: '%s'. Supported are: CN, SAN_DNS",
                field_string.c_str()));
    }
    auto match = req_entry["must-match"].asString().make_string();
    return RequiredPeerCredential(field, std::move(match));
}

PeerPolicy parse_peer_policy(const Inspector& peer_entry) {
    auto& creds = peer_entry["required-credentials"];
    if (creds.children() == 0) {
        throw IllegalArgumentException("\"required-credentials\" array can't be empty (would allow all peers)");
    }
    std::vector<RequiredPeerCredential> required_creds;
    for (size_t i = 0; i < creds.children(); ++i) {
        required_creds.emplace_back(parse_peer_credential(creds[i]));
    }
    return PeerPolicy(std::move(required_creds));
}

AuthorizedPeers parse_authorized_peers(const Inspector& authorized_peers) {
    if (!authorized_peers.valid()) {
        // If there's no "authorized-peers" object, valid CA signing is sufficient.
        return AuthorizedPeers::allow_all_authenticated();
    }
    if (authorized_peers.children() == 0) {
        throw IllegalArgumentException("\"authorized-peers\" must either be not present (allows "
                                       "all peers with valid certificates) or a non-empty array");
    }
    std::vector<PeerPolicy> policies;
    for (size_t i = 0; i < authorized_peers.children(); ++i) {
        policies.emplace_back(parse_peer_policy(authorized_peers[i]));
    }
    return AuthorizedPeers(std::move(policies));
}

std::unique_ptr<TransportSecurityOptions> load_from_input(Input& input) {
    Slime root;
    auto parsed = slime::JsonFormat::decode(input, root);
    if (parsed == 0) {
        throw IllegalArgumentException("Provided TLS config file is not valid JSON");
    }
    auto& files = root["files"];
    if (files.fields() == 0) {
        throw IllegalArgumentException("TLS config root field 'files' is missing or empty");
    }
    // Note: we do no look at the _contents_ of the files; this is deferred to the
    // TLS context code which actually tries to extract key and certificate material
    // from them.
    auto ca_certs = load_file_referenced_by_field(files, "ca-certificates");
    auto certs    = load_file_referenced_by_field(files, "certificates");
    auto priv_key = load_file_referenced_by_field(files, "private-key");
    auto authorized_peers = parse_authorized_peers(root["authorized-peers"]);

    return std::make_unique<TransportSecurityOptions>(std::move(ca_certs), std::move(certs),
                                                      std::move(priv_key), std::move(authorized_peers));
}

} // anon ns

std::unique_ptr<TransportSecurityOptions> read_options_from_json_string(const vespalib::string& json_data) {
    MemoryInput file_input(json_data);
    return load_from_input(file_input);
}

std::unique_ptr<TransportSecurityOptions> read_options_from_json_file(const vespalib::string& file_path) {
    MappedFileInput file_input(file_path);
    if (!file_input.valid()) {
        throw IllegalArgumentException(make_string("TLS config file '%s' could not be read", file_path.c_str()));
    }
    return load_from_input(file_input);
}

}
