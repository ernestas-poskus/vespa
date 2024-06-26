// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/document/base/globalid.h>
#include <vespa/searchlib/attribute/search_context.h>

namespace proton { class DocumentMetaStore; }
namespace proton::documentmetastore {

/**
 * Search context used to search the document meta store for all valid documents.
 */
class SearchContext : public search::attribute::SearchContext
{
private:
    using DocId = uint32_t;

    bool _isWord;
    document::GlobalId _gid;
    uint32_t _docid_limit;

    search::attribute::HitEstimate calc_hit_estimate() const override;
    int32_t onFind(DocId docId, int32_t elemId, int32_t &weight) const override;
    int32_t onFind(DocId docId, int32_t elemId) const override;

    std::unique_ptr<search::queryeval::SearchIterator>
    createIterator(search::fef::TermFieldMatchData *matchData, bool strict) override;

    const DocumentMetaStore &getStore() const;

public:
    SearchContext(std::unique_ptr<search::QueryTermSimple> qTerm, const DocumentMetaStore &toBeSearched);
    uint32_t get_committed_docid_limit() const noexcept override;
};

}
