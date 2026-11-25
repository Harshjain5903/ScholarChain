package edu.scholarchain.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ServiceConfigSpec extends AnyFlatSpec with Matchers:

  "ServiceConfig.fromEnv" should "use bounded defaults for the idempotency store and result TTL" in {
    val c = ServiceConfig.fromEnv(Map.empty)
    c.idempotencyTtlMs shouldBe 24L * 3600 * 1000
    c.idempotencyMaxEntries shouldBe 10000
    c.idempotencyMaxKeyLength shouldBe 255
    c.resultTtlDays shouldBe 30
    c.maxConcurrentQueries shouldBe 16
  }

  it should "read and clamp the overrides" in {
    val c = ServiceConfig.fromEnv(
      Map(
        "IDEMPOTENCY_TTL_SECONDS"    -> "99999999",
        "IDEMPOTENCY_MAX_ENTRIES"    -> "0",
        "IDEMPOTENCY_MAX_KEY_LENGTH" -> "64",
        "RESULT_TTL_DAYS"            -> "7",
        "MAX_CONCURRENT_QUERIES"     -> "1000",
        "MAX_GRAPH_DEPTH"            -> "9"
      )
    )
    c.idempotencyTtlMs shouldBe 7L * 86400 * 1000
    c.idempotencyMaxEntries shouldBe 1
    c.idempotencyMaxKeyLength shouldBe 64
    c.resultTtlDays shouldBe 7
    c.maxConcurrentQueries shouldBe 256
    c.maxGraphDepth shouldBe 3
  }
