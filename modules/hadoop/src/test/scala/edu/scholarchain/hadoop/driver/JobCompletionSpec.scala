package edu.scholarchain.hadoop.driver

import edu.scholarchain.hadoop.jobs.{GraphExportJob, PairExpansionJob, TeamExtractionJob}
import edu.scholarchain.core.{AuthorMention, Provenance}
import org.apache.hadoop.mapreduce.Counters
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class JobCompletionSpec extends AnyFunSuite with Matchers:

  test("countersJson renders groups and names sorted with values") {
    val c = new Counters()
    c.findCounter("ScholarChain", "rejected").increment(2)
    c.findCounter("ScholarChain", "accepted").increment(5)
    c.findCounter("RejectReasons", "MALFORMED_XML").increment(2)
    val json = JobCompletion.countersJson("dblp-extraction", succeeded = true, c)
    json("job").str shouldBe "dblp-extraction"
    json("succeeded").bool shouldBe true
    json("counters").obj.keys.toSeq shouldBe Seq("RejectReasons", "ScholarChain")
    json("counters")("ScholarChain").obj.keys.toSeq shouldBe Seq("accepted", "rejected")
    json("counters")("ScholarChain")("accepted").num shouldBe 5
    json("counters")("RejectReasons")("MALFORMED_XML").num shouldBe 2
  }

  test("countersJson tolerates null counters") {
    JobCompletion.countersJson("x", succeeded = false, null)("counters").obj shouldBe empty
  }

  test("PairExpansionJob recognizes J06 author-paper lines but not team lines") {
    val prov = Provenance("dblp", "dblp:k", "r", "1.0.0", "t")
    val m    = AuthorMention("mention:dblp:k:0", "A B", "a b", 0, None, None, None, "dblp:k", prov)
    val ap   = TeamExtractionJob.authorPaperJson(m, "work:1", "r", "1.0.0", "t", "dblp", "dblp:k")
    PairExpansionJob.isAuthorPaperRecord(ujson.read(ap)) shouldBe true
    val team = ujson.Obj("workId" -> "work:1", "authorMentions" -> ujson.Arr(), "teamSize" -> 0)
    PairExpansionJob.isAuthorPaperRecord(team) shouldBe false
    PairExpansionJob.isAuthorPaperRecord(ujson.Arr()) shouldBe false
  }

  test("GraphExportJob writes each named output to its own subdirectory") {
    GraphExportJob.basePath(GraphExportJob.PairCandidatesOutput) shouldBe "pair_candidates/part"
  }

  test("GraphExportJob named outputs are valid Hadoop tokens (letters and digits only)") {
    val dirs = Seq(
      GraphExportJob.WorkRecordsOutput,
      GraphExportJob.AuthorMentionsOutput,
      GraphExportJob.TeamsOutput,
      GraphExportJob.PairCandidatesOutput,
      GraphExportJob.AuthorPaperOutput,
      GraphExportJob.TopicsOutput,
      GraphExportJob.InstitutionsOutput,
      GraphExportJob.MalformedOutput
    )
    dirs.foreach(d => GraphExportJob.namedOutput(d) should fullyMatch regex "[A-Za-z0-9]+")
    dirs.map(GraphExportJob.namedOutput).distinct.size shouldBe dirs.size
    GraphExportJob.namedOutput("pair_candidates") shouldBe "paircandidates"
  }
