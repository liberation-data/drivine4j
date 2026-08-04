/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.drivine.query.dsl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.drivine.manager.GraphObjectManager;
import org.junit.jupiter.api.Test;

class JavaKeysetApiTest {

    @Test
    void compoundSeekIsAvailableFromTheJavaFluentApi() {
        TestDsl dsl = new TestDsl();
        JavaQueryBuilder<Object, TestDsl> builder =
                new JavaQueryBuilder<>(Object.class, dsl, mock(GraphObjectManager.class));

        JavaQueryBuilder<Object, TestDsl> result = builder
                .orderBy(q -> q.activity.desc())
                .orderBy(q -> q.id.desc())
                .seekAfter(q -> List.of(q.activity.after(42L), q.id.after("session-42")))
                .limit(21);

        assertSame(builder, result);
    }

    private static final class TestDsl {
        private final PropertyReference<Long> activity = new PropertyReference<>("session", "lastActivityAt");
        private final PropertyReference<String> id = new PropertyReference<>("session", "sessionId");
    }
}
