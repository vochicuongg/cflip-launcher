package com.cuong.cflip

import com.cuong.cflip.data.RecentSorter
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSorterTest {
    @Test fun mergesAndSortsByTimeDesc() {
        val merged = RecentSorter.merge(
            fromUsageStats = listOf("a" to 100L, "b" to 300L),
            fromHistory = mapOf("a" to 200L, "c" to 50L),
            ownPackage = "own",
        )
        assertEquals(listOf("b", "a", "c"), merged)
    }

    @Test fun removesOwnPackageAndLimits() {
        val merged = RecentSorter.merge(
            fromUsageStats = listOf("own" to 999L, "a" to 10L, "b" to 20L, "c" to 30L),
            fromHistory = emptyMap(),
            ownPackage = "own",
            limit = 2,
        )
        assertEquals(listOf("c", "b"), merged)
    }
}
