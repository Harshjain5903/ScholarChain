package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * STO-P13-01 (review finding N41): contract tests for parsing the response shape Neptune's
 * openCypher HTTPS endpoint returns. Fixtures mirror that shape: rows wrapped in
 * `{"results":[..]}`, nodes and relationships as `~id` / `~entityType` / `~labels` / `~type` /
 * `~properties` entities, and properties as loaded from the Spark `NeptuneBulkLoadExport` CSVs
 * (`provenance` and `representativeWorkIds` stored as JSON text, `:Int` columns as JSON numbers).
 * No AWS calls: the client tests use the fake transports from [[StorageTestAws]].
 */
class NeptuneResponseContractSpec extends AnyFlatSpec with Matchers:

  import StorageTestAws.*

  private val K = GraphSchema.PropertyKey

  private val provenance = Provenance(
    sourceDataset = "dblp",
    sourceRecordId = "conf/kdd/Smith19",
    pipelineRunId = "run-20261123-001",
    schemaVersion = "1.0.0",
    timestamp = "2026-11-23T10:00:00Z",
    transformationStep = Some("neptune-bulk-load-export"),
    producer = Some("NeptuneBulkLoadExport")
  )

  /** Provenance exactly as the bulk-load CSV stores it: JSON text inside a String property. */
  private val provenanceText: String = upickle.default.write(provenance)

  /** JSON string literal of `s`, for embedding text inside the fixtures below. */
  private def q(s: String): String = ujson.write(ujson.Str(s))

  private val ResearcherA = "researcher:orcid:0000-0001-0000-0001"
  private val ResearcherB = "researcher:orcid:0000-0002-0000-0002"

  private def researcherNode(id: String, displayName: Option[String] = Some("Ada Smith")) =
    val name = displayName.fold("")(n => s""", "displayName": ${q(n)}""")
    s"""{
       |  "~id": ${q(id)},
       |  "~entityType": "node",
       |  "~labels": ["Researcher"],
       |  "~properties": {
       |    "nodeId": ${q(id)},
       |    "researcherId": ${q(id)},
       |    "componentId": "component:7"$name,
       |    "provenance": ${q(provenanceText)}
       |  }
       |}""".stripMargin

  private val workNode: String =
    s"""{
       |  "~id": "work:dblp:conf/kdd/Smith19",
       |  "~entityType": "node",
       |  "~labels": ["Work"],
       |  "~properties": {
       |    "nodeId": "work:dblp:conf/kdd/Smith19",
       |    "workId": "work:dblp:conf/kdd/Smith19",
       |    "title": "Graph  Mining at Scale",
       |    "year": 2019,
       |    "venue": "conf/kdd",
       |    "provenance": ${q(provenanceText)}
       |  }
       |}""".stripMargin

  /** The seven CoauthorEdgeKeys properties as the bulk load stores them (JSON-text values). */
  private val coauthorProps: Vector[(String, String)] = Vector(
    K.ResearcherA            -> q(ResearcherA),
    K.ResearcherB            -> q(ResearcherB),
    K.JointPublicationCount  -> "3",
    K.FirstCollaborationYear -> "2019",
    K.LastCollaborationYear  -> "2024",
    K.RepresentativeWorkIds  -> q("""["work:dblp:a","work:dblp:b"]"""),
    K.Provenance             -> q(provenanceText)
  )

  private def coauthorRel(without: Option[String] = None): String =
    val props = coauthorProps
      .filterNot((k, _) => without.contains(k))
      .map((k, v) => s"    ${q(k)}: $v")
      .mkString(",\n")
    s"""{
       |  "~id": "coauthor:abc123",
       |  "~entityType": "relationship",
       |  "~start": ${q(ResearcherA)},
       |  "~end": ${q(ResearcherB)},
       |  "~type": "COAUTHORED_WITH",
       |  "~properties": {
       |$props
       |  }
       |}""".stripMargin

  private def results(rows: String*): ujson.Value =
    ujson.read(rows.mkString("""{"results":[""", ",", "]}"))

  private val expectedEdge = CoauthorEdge(
    researcherA = ResearcherA,
    researcherB = ResearcherB,
    jointPublicationCount = 3,
    firstCollaborationYear = 2019,
    lastCollaborationYear = 2024,
    representativeWorkIds = Vector("work:dblp:a", "work:dblp:b"),
    provenance = provenance
  )

  private def neptuneClient(transport: HttpTransport) =
    NeptuneStorageClient(
      endpoint = "scholarchain-dev.cluster-abc.us-east-2.neptune.amazonaws.com",
      port = 8182,
      region = "us-east-2",
      queryTimeoutMs = 5000,
      transport = transport,
      credentialsProvider = dummyCredentials,
      clock = fixedClock
    )

  // ---- node ----

  "NeptuneResponseParser (Neptune shape)" should "parse a Researcher node entity" in {
    val r = NeptuneResponseParser
      .parseOptionalResearcher(results(s"""{"r": ${researcherNode(ResearcherA)}}"""))
      .get
    r.researcherId shouldBe ResearcherA
    r.displayName shouldBe "Ada Smith"
    r.provenance shouldBe provenance
    r.orcid shouldBe None
    r.alternateNames shouldBe empty
  }

  it should "parse a Researcher node without displayName" in {
    val r = NeptuneResponseParser
      .parseOptionalResearcher(results(s"""{"r": ${researcherNode(ResearcherA, None)}}"""))
      .get
    r.researcherId shouldBe ResearcherA
    r.displayName shouldBe ""
  }

  it should "parse a Work node entity, mapping venue to venueId" in {
    val w = NeptuneResponseParser.parseOptionalWork(results(s"""{"w": $workNode}""")).get
    w.workId shouldBe "work:dblp:conf/kdd/Smith19"
    w.title shouldBe "Graph  Mining at Scale"
    w.normalizedTitle shouldBe "graph mining at scale"
    w.year shouldBe 2019
    w.venueId shouldBe Some("conf/kdd")
    w.source shouldBe SourceType.DBLP
    w.sourceRecordId shouldBe "conf/kdd/Smith19"
    w.provenance shouldBe provenance
  }

  it should "parse a Work node with no title, year or venue" in {
    val bare =
      s"""{"~id":"w9","~entityType":"node","~labels":["Work"],
         |"~properties":{"workId":"w9","provenance":${q(provenanceText)}}}""".stripMargin
    val w = NeptuneResponseParser.parseOptionalWork(results(s"""{"w": $bare}""")).get
    w.workId shouldBe "w9"
    w.title shouldBe ""
    w.year shouldBe 0
    w.venueId shouldBe None
    w.source shouldBe SourceType.ScholarChain
    w.sourceRecordId shouldBe provenance.sourceRecordId
  }

  it should "parse a node as a GraphNode with its declared label and string properties" in {
    val nodes =
      NeptuneResponseParser.parseGraphNodes(results(s"""{"neighbor": $workNode}"""))
    nodes should have size 1
    val n = nodes.head
    n.nodeId shouldBe "work:dblp:conf/kdd/Smith19"
    n.nodeType shouldBe GraphSchema.VertexLabel.Work
    n.properties(K.Year) shouldBe "2019"
    n.properties(K.Venue) shouldBe "conf/kdd"
    n.properties should not contain key(K.Provenance)
    n.provenance shouldBe provenance
  }

  it should "accept provenance as a native JSON object" in {
    val native = ujson.read(researcherNode(ResearcherA))
    native("~properties")(K.Provenance) = upickle.default.writeJs(provenance)
    val r = NeptuneResponseParser
      .parseOptionalResearcher(ujson.Obj("results" -> ujson.Arr(ujson.Obj("r" -> native))))
      .get
    r.provenance shouldBe provenance
  }

  // ---- COAUTHORED_WITH relationship ----

  it should "parse a COAUTHORED_WITH relationship with JSON-text list and provenance" in {
    NeptuneResponseParser.parseCoauthorEdges(results(s"""{"e": ${coauthorRel()}}""")) shouldBe
      Vector(expectedEdge)
  }

  it should "accept representativeWorkIds as a native array" in {
    val rel = ujson.read(coauthorRel())
    rel("~properties")(K.RepresentativeWorkIds) = ujson.Arr("work:dblp:a", "work:dblp:b")
    NeptuneResponseParser.parseCoauthorEdges(
      ujson.Obj("results" -> ujson.Arr(ujson.Obj("e" -> rel)))
    ) shouldBe Vector(expectedEdge)
  }

  it should "parse the relationship end to end through the signed client" in {
    val transport = RecordingTransport(ok(ujson.write(results(s"""{"e": ${coauthorRel()}}"""))))
    neptuneClient(transport).getDirectCoauthors(ResearcherA, 10) shouldBe Vector(expectedEdge)
  }

  it should "reject a relationship of a different ~type" in {
    val other = coauthorRel().replace("\"COAUTHORED_WITH\"", "\"AUTHORED\"")
    val e = the[NeptuneResponseFormatException] thrownBy
      NeptuneResponseParser.parseCoauthorEdges(results(s"""{"e": $other}"""))
    e.getMessage should include("expected type 'COAUTHORED_WITH', got 'AUTHORED'")
  }

  it should "reject representativeWorkIds text that is not a JSON array" in {
    val rel = ujson.read(coauthorRel())
    rel("~properties")(K.RepresentativeWorkIds) = ujson.Str("work:dblp:a;work:dblp:b")
    val e = the[NeptuneResponseFormatException] thrownBy
      NeptuneResponseParser.parseCoauthorEdges(
        ujson.Obj("results" -> ujson.Arr(ujson.Obj("e" -> rel)))
      )
    e.getMessage should include(s"'${K.RepresentativeWorkIds}'")
  }

  // One negative test per required COAUTHORED_WITH key.
  GraphSchema.PropertyKey.CoauthorEdgeKeys.foreach { key =>
    it should s"fail clearly when a COAUTHORED_WITH relationship lacks '$key'" in {
      val e = the[NeptuneResponseFormatException] thrownBy
        NeptuneResponseParser.parseCoauthorEdges(results(s"""{"e": ${coauthorRel(Some(key))}}"""))
      e.getMessage shouldBe
        s"COAUTHORED_WITH relationship 'coauthor:abc123' is missing required property '$key'"
    }
  }

  it should "cover every CoauthorEdgeKeys entry with a fixture property" in {
    coauthorProps.map(_._1) shouldBe GraphSchema.PropertyKey.CoauthorEdgeKeys
  }

  it should "fail clearly when a Researcher node lacks researcherId or provenance" in {
    Vector(K.ResearcherId, K.Provenance).foreach { key =>
      val node = ujson.read(researcherNode(ResearcherA))
      node("~properties").obj.remove(key)
      val e = the[NeptuneResponseFormatException] thrownBy
        NeptuneResponseParser.parseOptionalResearcher(
          ujson.Obj("results" -> ujson.Arr(ujson.Obj("r" -> node)))
        )
      e.getMessage should include(s"missing required property '$key'")
    }
  }

  // ---- path ----

  it should "parse a path result as alternating node/relationship entities" in {
    val researcherC = "researcher:sc:abc"
    val rel2 = coauthorRel()
      .replace("coauthor:abc123", "coauthor:def456")
      .replace(q(ResearcherA), q(researcherC))
    val path = s"""{"p": [${researcherNode(ResearcherA)}, ${coauthorRel()}, ${researcherNode(
        ResearcherB
      )}, $rel2, ${researcherNode(researcherC)}]}"""
    NeptuneResponseParser.parsePath(results(path)) shouldBe
      Some(Vector(ResearcherA, ResearcherB, researcherC))
  }

  it should "reject a path whose elements do not alternate node and relationship" in {
    val bad = s"""{"p": [${researcherNode(ResearcherA)}, ${researcherNode(ResearcherB)}]}"""
    val e = the[NeptuneResponseFormatException] thrownBy
      NeptuneResponseParser.parsePath(results(bad))
    e.getMessage should include("expected a relationship at an odd path position")
  }

  it should "still parse the projected path ID list the client query returns" in {
    NeptuneResponseParser.parsePath(results("""{"path": ["r1", "r2"]}""")) shouldBe
      Some(Vector("r1", "r2"))
  }

  // ---- empty result ----

  it should "return nothing for an empty Neptune result" in {
    val emptyResponse = ujson.read("""{"results":[]}""")
    NeptuneResponseParser.parseOptionalResearcher(emptyResponse) shouldBe None
    NeptuneResponseParser.parseOptionalWork(emptyResponse) shouldBe None
    NeptuneResponseParser.parseCoauthorEdges(emptyResponse) shouldBe empty
    NeptuneResponseParser.parsePath(emptyResponse) shouldBe None
    NeptuneResponseParser.parseGraphNodes(emptyResponse) shouldBe empty
    NeptuneResponseParser.parseStringList(
      emptyResponse,
      NeptuneResponseParser.Column.ResearcherIds
    ) shouldBe
      empty
  }

  it should "return None from the client for an empty Neptune result" in {
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    val client    = neptuneClient(transport)
    client.getResearcher(ResearcherA) shouldBe None
    client.getWork("work:dblp:x") shouldBe None
    client.getDirectCoauthors(ResearcherA, 5) shouldBe empty
    client.shortestPath(ResearcherA, ResearcherB, 3) shouldBe None
  }

  it should "fail clearly when a row lacks the expected column" in {
    val e = the[NeptuneResponseFormatException] thrownBy
      NeptuneResponseParser.parseOptionalResearcher(results("""{"x": 1}"""))
    e.getMessage shouldBe "result row 0 has no column 'r'"
  }
