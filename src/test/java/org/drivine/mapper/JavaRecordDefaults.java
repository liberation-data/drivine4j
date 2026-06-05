package org.drivine.mapper;

import org.drivine.annotation.EmptyWhenAbsent;

import java.util.List;
import java.util.Map;

/**
 * Java record fixture for {@link org.drivine.annotation.EmptyWhenAbsent} — records have no field
 * initializer, so this is the Java case the annotation exists for.
 */
public record JavaRecordDefaults(
        String name,
        @EmptyWhenAbsent List<String> roles,
        @EmptyWhenAbsent Map<String, String> attributes
) {
}