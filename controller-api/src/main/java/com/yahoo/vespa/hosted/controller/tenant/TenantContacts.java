// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.tenant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Tenant contacts are targets of the notification system.  Sometimes they
 * are a person with an email address, other times they are a Slack channel,
 * IRC plugin, etc.
 *
 * @author ogronnesby
 */
public class TenantContacts {
    private final List<Contact<?>> contacts;

    public TenantContacts() {
        this.contacts = new ArrayList<>();
    }

    public static TenantContacts empty() {
        return new TenantContacts();
    }

    public static TenantContacts from(List<Contact<?>> contacts) {
        var tc = new TenantContacts();
        contacts.forEach(tc::add);
        return tc;
    }

    public <T> void add(Contact<T> contact) {
        contacts.removeIf(c -> c.data().equals(contact.data()));
        contacts.add(contact);
    }

    public List<Contact<?>> all() {
        return List.copyOf(contacts);
    }

    public boolean isEmpty() {
        return contacts.isEmpty();
    }

    public static class Contact<T> {
        private final Type type;
        private final Audience audience;
        protected final T data;

        public Contact(Type type, Audience audience, T data) {
            this.type = type;
            this.audience = audience;
            this.data = data;
        }

        public Type type() { return type; }
        public Audience audience() { return audience; }
        public T data() { return data; }

        @Override
        public String toString() {
            return "Contact{" +
                    "type=" + type +
                    ", audience=" + audience +
                    ", data=" + data +
                    '}';
        }
    }

    public static class EmailContact {
        private final String email;

        public EmailContact(String email) {
            this.email = email;
        }

        public String email() { return email; }

        @Override
        public String toString() {
            return "EmailContact{" +
                    "email='" + email + '\'' +
                    '}';
        }
    }

    public enum Type {
        EMAIL("email");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String value() {
            return this.value;
        }

        public static Optional<Type> from(String value) {
            return Arrays.stream(Type.values()).filter(x -> x.value().equals(value)).findAny();
        }
    }

    public enum Audience {
        // tenant admin type updates about billing etc.
        TENANT("tenant"),

        // system notifications like deployment failures etc.
        NOTIFICATIONS("notifications");

        private final String value;

        Audience(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Optional<Audience> from(String value) {
            return Arrays.stream(Audience.values()).filter((x -> x.value().equals(value))).findAny();
        }
    }
}
