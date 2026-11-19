package edu.scholarchain.hadoop.jobs

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GraphExportSpec extends AnyFunSuite with Matchers:

  test("detectRecordType identifies work_records") {
    val json = ujson.Obj(
      "workId"          -> "work:dblp:key1",
      "source"          -> "DBLP",
      "sourceRecordId"  -> "dblp:key1",
      "title"           -> "Test",
      "normalizedTitle" -> "test",
      "year"            -> 2023,
      "authors"         -> ujson.Arr(),
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "work_records"
  }

  test("detectRecordType identifies author_mentions") {
    val json = ujson.Obj(
      "mentionId"      -> "mention:dblp:key1:0",
      "rawName"        -> "Test Author",
      "normalizedName" -> "test author",
      "position"       -> 0,
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "author_mentions"
  }

  test("detectRecordType identifies teams") {
    val json = ujson.Obj(
      "workId"          -> "work:test:1",
      "authorMentions"  -> ujson.Arr(),
      "teamSize"        -> 3,
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "teams"
  }

  test("detectRecordType identifies pair_candidates") {
    val json = ujson.Obj(
      "mentionA" -> "m:a",
      "mentionB" -> "m:b",
      "workId"   -> "work:test:1",
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "pair_candidates"
  }

  test("detectRecordType identifies author_paper records") {
    val json = ujson.Obj(
      "mention_id" -> "mention:dblp:key1:0",
      "work_id"    -> "work:dblp:key1",
      "position"   -> 0,
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "author_paper"
  }

  test("detectRecordType identifies topics") {
    val json = ujson.Obj(
      "topicId" -> "topic:ml",
      "name"    -> "Machine Learning",
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "topics"
  }

  test("detectRecordType identifies institutions") {
    val json = ujson.Obj(
      "institutionId" -> "inst:mit",
      "name"          -> "MIT",
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "institutions"
  }

  test("detectRecordType returns other for unknown JSON") {
    val json = ujson.Obj(
      "unknownField" -> "value"
    ).toString()
    GraphExportJob.detectRecordType(json) shouldBe "other"
  }

  test("detectRecordType returns malformed for invalid JSON") {
    GraphExportJob.detectRecordType("{bad json") shouldBe "malformed"
  }

  test("detectRecordType returns malformed for non-JSON text") {
    GraphExportJob.detectRecordType("not json at all") shouldBe "malformed"
  }
