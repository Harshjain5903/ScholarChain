package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Minimal smoke test to verify the build compiles and test framework runs. */
class BuildSmokeSpec extends AnyFlatSpec with Matchers {

  "The build" should "compile and run tests" in {
    1 + 1 shouldBe 2
  }
}
