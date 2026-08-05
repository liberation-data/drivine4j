/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.query.dsl.IndexAdvicePolicy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals

/**
 * `drivine.query.index-advice` binds and reaches the managers the factory hands out.
 *
 * The property exists to be set in `application.yml` — turning the check up in development or CI is
 * the whole point of it — so the wiring that matters runs from a real Spring context, through
 * `DrivineQueryProperties` and `GraphObjectManagerFactory`, rather than being asserted on a manager
 * constructed by hand.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@TestPropertySource(properties = ["drivine.query.index-advice=FAIL"])
class IndexAdvicePropertyTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
) {

    @Test
    fun `the property reaches the manager the factory produced`() {
        assertEquals(IndexAdvicePolicy.FAIL, graphObjectManager.indexAdvice)
    }
}

/**
 * With nothing configured, the manager warns rather than failing — ordering without an index is
 * correct, just unindexed, so an unset property must not break an application that was fine before.
 */
@SpringBootTest(classes = [SampleAppContext::class])
class IndexAdviceDefaultTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
) {

    @Test
    fun `the default is WARN when the property is absent`() {
        assertEquals(IndexAdvicePolicy.WARN, graphObjectManager.indexAdvice)
    }
}
