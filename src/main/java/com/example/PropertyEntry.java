package com.example;

import java.util.List;

public class PropertyEntry {
    String key;
    List<String> lines;
    EntryType type;

    public PropertyEntry(String key, List<String> lines, EntryType type) {
        this.key = key;
        this.lines = lines;
        this.type = type;
    }

    public enum EntryType {
        PROPERTY, COMMENT, EMPTY_LINE
    }
}