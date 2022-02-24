// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.parser;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class holds the extracted information after parsing a "struct"
 * block, using simple data structures as far as possible.  Do not put
 * advanced logic here!
 * @author arnej27959
 **/
public class ParsedStruct {
    private final String name;
    private final List<String> inherited = new ArrayList<>();
    private final Map<String, ParsedField> fields = new HashMap<>();

    public ParsedStruct(String name) {
        this.name = name;
    }

    String name() { return this.name; }
    List<ParsedField> getFields() { return List.copyOf(fields.values()); }
    List<String> getInherited() { return List.copyOf(inherited); }

    void addField(ParsedField field) {
        String fieldName = field.name();
        if (fields.containsKey(fieldName)) {
            throw new IllegalArgumentException("struct "+this.name+" already has field "+fieldName);
        }
        fields.put(fieldName, field);
    }

    void inherit(String other) { inherited.add(other); }
}

