# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_executable(documentapi_replymerger_test_app TEST
    SOURCES
    replymerger_test.cpp
    DEPENDS
    documentapi
    GTest::gtest
)
vespa_add_test(NAME documentapi_replymerger_test_app COMMAND documentapi_replymerger_test_app)
