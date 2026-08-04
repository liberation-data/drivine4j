/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.drivine.query.dsl

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class KeysetPlannerTest {

    @Test
    fun `compound descending cursor expands lexicographically and reuses bindings`() {
        val instant = Instant.parse("2026-08-04T12:00:00Z")

        val plan = KeysetPlanner.plan(
            orders = listOf(
                OrderSpec("session.lastActivityAt", OrderDirection.DESC),
                OrderSpec("session.sessionId", OrderDirection.DESC),
            ),
            values = listOf(
                SeekValueSpec("session.lastActivityAt", instant),
                SeekValueSpec("session.sessionId", "s-20"),
            ),
        )

        assertEquals(
            "(session.lastActivityAt < \$_seek_0 OR " +
                "(session.lastActivityAt = \$_seek_0 AND session.sessionId < \$_seek_1))",
            plan.predicate,
        )
        assertEquals(mapOf("_seek_0" to instant, "_seek_1" to "s-20"), plan.bindings)
    }

    @Test
    fun `mixed directions use the comparison belonging to each order`() {
        val plan = KeysetPlanner.plan(
            orders = listOf(
                OrderSpec("n.priority", OrderDirection.DESC),
                OrderSpec("n.name", OrderDirection.ASC),
                OrderSpec("n.id", OrderDirection.DESC),
            ),
            values = listOf(
                SeekValueSpec("n.priority", 10),
                SeekValueSpec("n.name", "Ada"),
                SeekValueSpec("n.id", "id-7"),
            ),
        )

        assertEquals(
            "(n.priority < \$_seek_0 OR " +
                "(n.priority = \$_seek_0 AND n.name > \$_seek_1) OR " +
                "(n.priority = \$_seek_0 AND n.name = \$_seek_1 AND n.id < \$_seek_2))",
            plan.predicate,
        )
    }

    @Test
    fun `cursor paths and order paths must match`() {
        val error = assertThrows<IllegalArgumentException> {
            KeysetPlanner.plan(
                orders = listOf(OrderSpec("n.createdAt", OrderDirection.DESC)),
                values = listOf(SeekValueSpec("n.id", "id-1")),
            )
        }

        assertEquals(
            "seekAfter property #1 is n.id, but orderBy property #1 is n.createdAt",
            error.message,
        )
    }

    @Test
    fun `typed seekAfter DSL records cursor components`() {
        data class TestQuery(
            val activity: PropertyReference<Instant> = PropertyReference("n", "activity"),
            val id: PropertyReference<String> = PropertyReference("n", "id"),
        )

        val instant = Instant.parse("2026-08-04T12:00:00Z")
        val spec = GraphQuerySpec(TestQuery()).apply {
            orderBy {
                query.activity.desc()
                query.id.desc()
            }
            seekAfter {
                query.activity after instant
                query.id after "id-7"
            }
        }

        assertEquals(
            listOf(
                SeekValueSpec("n.activity", instant),
                SeekValueSpec("n.id", "id-7"),
            ),
            spec.seekValues,
        )
    }

    @Test
    fun `null cursor values are rejected`() {
        val property = PropertyReference<String?>("n", "nullable")
        assertThrows<IllegalArgumentException> { property.after(null) }
    }

    @Test
    fun `cursor values use the same database conversion as where bindings`() {
        val id = UUID.fromString("018f5d6e-6d78-7c3a-9b2f-0123456789ab")

        val plan = KeysetPlanner.plan(
            orders = listOf(OrderSpec("n.id", OrderDirection.ASC)),
            values = listOf(SeekValueSpec("n.id", id)),
        )

        assertEquals(id.toString(), plan.bindings["_seek_0"])
    }
}
