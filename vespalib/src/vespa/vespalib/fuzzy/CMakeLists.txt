# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(vespalib_vespalib_fuzzy OBJECT
    SOURCES
        explicit_levenshtein_dfa.cpp
        fuzzy_matcher.cpp
        fuzzy_matching_algorithm.cpp
        implicit_levenshtein_dfa.cpp
        levenshtein_dfa.cpp
        levenshtein_distance.cpp
        table_dfa.cpp
        unicode_utils.cpp
    DEPENDS
)

