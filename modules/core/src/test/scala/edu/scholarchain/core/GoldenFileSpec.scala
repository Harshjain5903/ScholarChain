package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/** Tests that golden files deserialize correctly and re-serialize to equivalent JSON. */
class GoldenFileSpec extends AnyFlatSpec with Matchers {
  import TestFixtures.*

  private def loadResource(name: String): String =
    val stream = getClass.getResourceAsStream(s"/$name")
    require(stream != null, s"Resource $name not found on classpath")
    scala.io.Source.fromInputStream(stream).mkString

  "golden-work-record.json" should "deserialize to the expected WorkRecord" in {
    val json = loadResource("golden-work-record.json")
    val record = read[WorkRecord](json)
    record shouldBe sampleWorkRecord
  }

  it should "re-serialize to equivalent JSON" in {
    val goldenJson = loadResource("golden-work-record.json")
    val goldenParsed = ujson.read(goldenJson)
    val record = read[WorkRecord](goldenJson)
    val reserialized = ujson.read(write(record))
    reserialized shouldBe goldenParsed
  }

  "golden-match-decision.json" should "deserialize to the expected MatchDecision" in {
    val json = loadResource("golden-match-decision.json")
    val record = read[MatchDecision](json)
    record shouldBe sampleMatchDecision
  }

  it should "re-serialize to equivalent JSON" in {
    val goldenJson = loadResource("golden-match-decision.json")
    val goldenParsed = ujson.read(goldenJson)
    val record = read[MatchDecision](goldenJson)
    val reserialized = ujson.read(write(record))
    reserialized shouldBe goldenParsed
  }

  "golden-coauthor-edge.json" should "deserialize to the expected CoauthorEdge" in {
    val json = loadResource("golden-coauthor-edge.json")
    val record = read[CoauthorEdge](json)
    record shouldBe sampleCoauthorEdge
  }

  it should "re-serialize to equivalent JSON" in {
    val goldenJson = loadResource("golden-coauthor-edge.json")
    val goldenParsed = ujson.read(goldenJson)
    val record = read[CoauthorEdge](goldenJson)
    val reserialized = ujson.read(write(record))
    reserialized shouldBe goldenParsed
  }
}
