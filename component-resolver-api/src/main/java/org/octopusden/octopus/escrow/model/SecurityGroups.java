package org.octopusden.octopus.escrow.model;

import java.util.Objects;

public class SecurityGroups {
    private final String read;

    public SecurityGroups(String read) {
        this.read = read;
    }

    public String getRead() {
        return read;
    }

    @Override
    public String toString() {
        return "SecurityGroups{" +
                "read= '" + read + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SecurityGroups that = (SecurityGroups) o;
        return Objects.equals(read, that.read);
    }

    @Override
    public int hashCode() {
        return Objects.hash(read);
    }
}
