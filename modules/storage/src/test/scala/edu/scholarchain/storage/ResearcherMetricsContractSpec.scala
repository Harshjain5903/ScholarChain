package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * STO-P13-02: contract tests for reading Spark-computed [[ResearcherMetrics]] from `Researcher`
 * vertices. Fixtures mirror Neptune's openCypher HTTPS response shape for vertices bulk-loaded from
 * typed CSV columns: `:Int` / `:Long` counts and years as JSON integers, `:Double` averages and
 * scores as JSON numbers with a fraction or exponent, `:String` IDs and `bridgeScoreMethod` as
 * strings, `maximumTeamWorkIds` and `provenance` as JSON text in String properties. No AWS calls:
 * fake transports from [[StorageTestAws]] only.
 */
class ResearcherMetricsContractSpec extends AnyFlatSpec with Matchers:

  import StorageTestAws.*

  private val K = GraphSchema.PropertyKey

  private val provenance = Provenance(
    sourceDataset = "dblp",
    sourceRecordId = "pid/42/1234",
    pipelineRunId = "run-20261124-001",
    schemaVersion = "1.0.0",
    timestamp = "2026-11-24T08:00:00Z",
    transformationStep = Some("researcher-metrics"),
    producer = Some("NeptuneBulkLoadExport")
  )

  private val provenanceText = upickle.default.write(provenance)

  private def q(s: String): String = ujson.write(ujson.Str(s))

  private val Ada   = "researcher:orcid:0000-0001-0000-0001"
  private val Grace = "researcher:orcid:0000-0002-0000-0002"

  /** Metric properties exactly as Neptune serializes the typed bulk-load columns (raw JSON). */
  private val fullMetricProps: Vector[(String, String)] = Vector(
    K.PublicationCount           -> "12",
    K.DistinctCoauthorCount      -> "9",
    K.TotalCoauthorOccurrences   -> "30",
    K.AverageCoauthorsPerPaper   -> "2.5",
    K.MaximumCoauthorsOnOnePaper -> "6",
    K.MaximumTeamWorkIds -> q("""["work:dblp:conf/kdd/Smith19","work:dblp:conf/www/Smith21"]"""),
    K.WeightedDegree     -> "7.0",
    K.ComponentId        -> q("component:7"),
    K.ComponentSize      -> "1540",
    K.CommunityId        -> q("community:3"),
    K.BridgeScore        -> "1.0E-4",
    K.BridgeScoreMethod  -> q("sampled"),
    K.Coreness           -> "4",
    K.WindowStartYear    -> "2015",
    K.WindowEndYear      -> "2024"
  )

  private def researcherVertex(
    id: String,
    metrics: Vector[(String, String)] = fullMetricProps,
    overrides: Map[String, String] = Map.empty
  ): String =
    val metricKeys = metrics.map(_._1).toSet
    val props =
      (Vector(
        K.NodeId       -> q(id),
        K.ResearcherId -> q(id),
        K.DisplayName  -> q("Ada Smith"),
        K.Provenance   -> q(provenanceText)
      ) ++ metrics.map((k, v) => k -> overrides.getOrElse(k, v)) ++
        overrides.toVector.filterNot((k, _) => metricKeys.contains(k)))
        .map((k, v) => s"    ${q(k)}: $v")
        .mkString(",\n")
    s"""{
       |  "~id": ${q(id)},
       |  "~entityType": "node",
       |  "~labels": ["Researcher"],
       |  "~properties": {
       |$props
       |  }
       |}""".stripMargin

  private def results(rows: String*): String = rows.mkString("""{"results":[""", ",", "]}")

  private def row(vertex: String): String = s"""{"r": $vertex}"""

  private val expectedAda = ResearcherMetrics(
    researcherId = Ada,
    publicationCount = 12,
    distinctCoauthorCount = 9,
    totalCoauthorOccurrences = 30,
    averageCoauthorsPerPaper = 2.5,
    maximumCoauthorsOnOnePaper = 6,
    weightedDegree = 7.0,
    componentId = Some("component:7"),
    communityId = Some("community:3"),
    bridgeScore = 1.0e-4,
    coreness = 4,
    windowStartYear = 2015,
    windowEndYear = 2024,
    provenance = provenance,
    maximumTeamWorkIds = Vector("work:dblp:conf/kdd/Smith19", "work:dblp:conf/www/Smith21"),
    bridgeScoreMethod = BridgeScoreMethod.Sampled,
    componentSize = Some(1540L)
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

  private def form(call: HttpCall): Map[String, String] =
    call.bodyString
      .split("&")
      .toVector
      .map { kv =>
        val Array(k, v) = kv.split("=", 2)
        k -> URLDecoder.decode(v, StandardCharsets.UTF_8)
      }
      .toMap

  private def window = MetricWindow(2015, 2024)

  // ---- full metrics ----

  "NeptuneStorageClient.getResearcherMetrics" should "decode a vertex with full typed metrics" in {
    val transport = RecordingTransport(ok(results(row(researcherVertex(Ada)))))
    neptuneClient(transport).getResearcherMetrics(Ada) shouldBe Some(expectedAda)

    val sent = form(transport.calls.head)
    sent("query") shouldBe "MATCH (r:Researcher {researcherId: $rid}) RETURN r LIMIT 2"
    ujson.read(sent("parameters")) shouldBe ujson.Obj("rid" -> Ada)
  }

  it should "round-trip through the core codec: decoded metrics re-encode to the same values" in {
    val m = neptuneClient(RecordingTransport(ok(results(row(researcherVertex(Ada))))))
      .getResearcherMetrics(Ada)
      .get
    ResearcherMetrics.toGraphProperties(m).toMap shouldBe
      ResearcherMetrics.toGraphProperties(expectedAda).toMap
  }

  it should "pass a hostile researcher ID only as a parameter" in {
    val hostile   = "x'}) MATCH (n) DETACH DELETE n //"
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    neptuneClient(transport).getResearcherMetrics(hostile) shouldBe None
    val sent = form(transport.calls.head)
    sent("query") should not include "DETACH"
    ujson.read(sent("parameters")) shouldBe ujson.Obj("rid" -> hostile)
  }

  // ---- absent metrics ----

  it should "return None when no vertex has the ID" in {
    neptuneClient(RecordingTransport(ok("""{"results":[]}"""))).getResearcherMetrics(Ada) shouldBe
      None
  }

  it should "return None when the vertex carries no metric properties" in {
    val bare = researcherVertex(Ada, metrics = Vector.empty)
    neptuneClient(RecordingTransport(ok(results(row(bare))))).getResearcherMetrics(Ada) shouldBe
      None
  }

  it should "return None when only the shared lookup key componentId is present" in {
    val withComponent = researcherVertex(Ada, metrics = Vector(K.ComponentId -> q("component:7")))
    neptuneClient(RecordingTransport(ok(results(row(withComponent)))))
      .getResearcherMetrics(Ada) shouldBe None
  }

  it should "treat null metric values as absent" in {
    val nulls = researcherVertex(Ada, metrics = fullMetricProps.map((k, _) => k -> "null"))
    neptuneClient(RecordingTransport(ok(results(row(nulls))))).getResearcherMetrics(Ada) shouldBe
      None
  }

  it should "accept absent optional metrics (component, community, team work IDs)" in {
    val optional = NeptuneResponseParser.OptionalMetricKeys
    val vertex = researcherVertex(
      Ada,
      metrics = fullMetricProps.filterNot((k, _) => optional.contains(k)),
      overrides = Map(K.MaximumCoauthorsOnOnePaper -> "0")
    )
    val m =
      neptuneClient(RecordingTransport(ok(results(row(vertex))))).getResearcherMetrics(Ada).get
    m.componentId shouldBe None
    m.communityId shouldBe None
    m.componentSize shouldBe None
    m.maximumTeamWorkIds shouldBe empty
  }

  // ---- malformed metrics ----

  private def malformed(vertex: String): NeptuneResponseFormatException =
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(vertex))))).getResearcherMetrics(Ada)
    }

  it should "fail with a typed error when some required metrics are missing" in {
    val partial = researcherVertex(
      Ada,
      metrics = fullMetricProps.filterNot((k, _) => k == K.Coreness || k == K.BridgeScoreMethod)
    )
    val ex = malformed(partial)
    ex.getMessage should include("incomplete researcher metrics")
    ex.getMessage should include(s"'${K.BridgeScoreMethod}'")
    ex.getMessage should include(s"'${K.Coreness}'")
  }

  it should "fail with a typed error on a fractional count" in {
    val ex = malformed(researcherVertex(Ada, overrides = Map(K.PublicationCount -> "12.5")))
    ex.getMessage should include(s"'${K.PublicationCount}'")
    ex.getMessage should include(Ada)
  }

  it should "fail with a typed error on a non-numeric string in a numeric metric, without echoing it" in {
    val ex = malformed(researcherVertex(Ada, overrides = Map(K.WeightedDegree -> q("SECRETish"))))
    ex.getMessage should include(s"'${K.WeightedDegree}'")
    ex.getMessage should not include "SECRETish"
  }

  it should "fail with a typed error on an unknown bridgeScoreMethod" in {
    val ex = malformed(researcherVertex(Ada, overrides = Map(K.BridgeScoreMethod -> q("guessed"))))
    ex.getMessage should include(s"'${K.BridgeScoreMethod}'")
    ex.getMessage should not include "guessed"
  }

  it should "fail with a typed error on maximumTeamWorkIds that is not a JSON array of strings" in {
    val ex = malformed(researcherVertex(Ada, overrides = Map(K.MaximumTeamWorkIds -> q("[1,2"))))
    ex.getMessage should include(s"'${K.MaximumTeamWorkIds}'")
  }

  it should "fail with a typed error on a boolean metric value" in {
    val ex = malformed(researcherVertex(Ada, overrides = Map(K.Coreness -> "true")))
    ex.getMessage should include(s"'${K.Coreness}'")
    ex.getMessage should include("boolean")
  }

  it should "fail with a typed error when values violate a ResearcherMetrics invariant" in {
    val ex = malformed(
      researcherVertex(
        Ada,
        overrides = Map(K.BridgeScoreMethod -> q("not-computed"), K.BridgeScore -> "0.37")
      )
    )
    ex.getMessage should include("invariant")
    ex.getMessage should not include "0.37"
  }

  it should "fail when duplicate vertices share the researcher ID" in {
    val v = researcherVertex(Ada)
    val ex = intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(v), row(v))))).getResearcherMetrics(Ada)
    }
    ex.getMessage should include("at most one")
  }

  it should "fail when the vertex returned has a different researcherId" in {
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(researcherVertex(Grace))))))
        .getResearcherMetrics(Ada)
    }
  }

  // ---- integer vs double formatting ----

  "NeptuneResponseParser.metricPropertyText" should
    "render integer metrics as plain digits, never with a trailing .0" in {
      // Neptune may serialize an :Int column as 3 or (via a double path) 3.0; ujson reads both as 3.0.
      for raw <- Vector("3", "3.0") do
        NeptuneResponseParser.metricPropertyText(
          "ctx",
          K.PublicationCount,
          ujson.read(raw)
        ) shouldBe
          "3"
      NeptuneResponseParser.metricPropertyText(
        "ctx",
        K.ComponentSize,
        ujson.read("4000000000")
      ) shouldBe
        "4000000000"
      NeptuneResponseParser.metricPropertyText(
        "ctx",
        K.WindowStartYear,
        ujson.read("2015")
      ) shouldBe
        "2015"
    }

  it should "render double metrics with Double.toString" in {
    def text(key: String, raw: String) =
      NeptuneResponseParser.metricPropertyText("ctx", key, ujson.read(raw))
    text(K.WeightedDegree, "3") shouldBe "3.0"
    text(K.WeightedDegree, "7.0") shouldBe "7.0"
    text(K.AverageCoauthorsPerPaper, "2.86") shouldBe "2.86"
    text(K.BridgeScore, "1.0E-4") shouldBe java.lang.Double.toString(1.0e-4)
    text(K.BridgeScore, "0.0001") shouldBe "1.0E-4"
  }

  it should "pass strings through and write native work-ID arrays compactly" in {
    NeptuneResponseParser.metricPropertyText(
      "ctx",
      K.BridgeScoreMethod,
      ujson.Str("exact")
    ) shouldBe
      "exact"
    NeptuneResponseParser.metricPropertyText(
      "ctx",
      K.MaximumTeamWorkIds,
      ujson.Arr("W1", "W2")
    ) shouldBe """["W1","W2"]"""
  }

  it should "reject fractional and beyond-2^53 numbers for integer metrics" in {
    for raw <- Vector("3.5", "1.0E17") do
      intercept[NeptuneResponseFormatException] {
        NeptuneResponseParser.metricPropertyText("ctx", K.Coreness, ujson.read(raw))
      }
  }

  it should "classify every declared metric key exactly once" in {
    val groups = Vector(
      NeptuneResponseParser.IntegerMetricKeys,
      NeptuneResponseParser.DoubleMetricKeys,
      NeptuneResponseParser.StringMetricKeys,
      NeptuneResponseParser.JsonArrayMetricKeys
    )
    groups.map(_.size).sum shouldBe K.ResearcherMetricKeys.size
    groups.reduce(_ ++ _) shouldBe K.ResearcherMetricKeys.toSet
  }

  "A decoded vertex" should "keep integer-typed and whole-double-typed values distinct" in {
    // Counts arrive as JSON integers, weightedDegree as a whole double 7.0; both decode exactly.
    val m = NeptuneResponseParser
      .parseOptionalResearcherMetrics(ujson.read(results(row(researcherVertex(Ada)))))
      .get
    m.publicationCount shouldBe 12
    m.weightedDegree shouldBe 7.0
    ResearcherMetrics.toGraphProperties(m).toMap.apply(K.PublicationCount) shouldBe "12"
    ResearcherMetrics.toGraphProperties(m).toMap.apply(K.WeightedDegree) shouldBe "7.0"
  }

  // ---- batch ----

  "NeptuneStorageClient.getResearcherMetricsBatch" should
    "return metrics keyed by ID, omitting unknown IDs and vertices without metrics" in {
      val graceBare = researcherVertex(Grace, metrics = Vector.empty)
      val transport = RecordingTransport(ok(results(row(researcherVertex(Ada)), row(graceBare))))
      val out =
        neptuneClient(transport).getResearcherMetricsBatch(Vector(Ada, Grace, "r:unknown", Ada))
      out shouldBe Map(Ada -> expectedAda)

      val sent = form(transport.calls.head)
      sent("query") shouldBe
        "MATCH (r:Researcher)\nWHERE r.researcherId IN $rids\nRETURN r LIMIT $lim"
      ujson.read(sent("parameters")) shouldBe
        ujson.Obj("rids" -> ujson.Arr(Ada, Grace, "r:unknown"), "lim" -> 4)
    }

  it should "accept exactly 100 distinct IDs" in {
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    val ids       = (1 to 100).map(i => s"r:$i")
    neptuneClient(transport).getResearcherMetricsBatch(ids) shouldBe empty
    transport.calls should have size 1
  }

  it should "reject more than 100 distinct IDs before any request" in {
    val ids = (1 to 101).map(i => s"r:$i")
    val ex = intercept[IllegalArgumentException] {
      neptuneClient(NoNetwork).getResearcherMetricsBatch(ids)
    }
    ex.getMessage should include("at most 100")
  }

  it should "apply the cap after collapsing duplicate IDs" in {
    val ids       = (1 to 100).map(i => s"r:$i") ++ (1 to 50).map(i => s"r:$i")
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    neptuneClient(transport).getResearcherMetricsBatch(ids) shouldBe empty
  }

  it should "send no request for an empty batch" in {
    neptuneClient(NoNetwork).getResearcherMetricsBatch(Vector.empty) shouldBe empty
  }

  it should "fail with a typed error when any vertex in the batch is malformed" in {
    val bad = researcherVertex(Grace, overrides = Map(K.Coreness -> "2.5"))
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(researcherVertex(Ada)), row(bad)))))
        .getResearcherMetricsBatch(Vector(Ada, Grace))
    }
  }

  it should "fail on a vertex that was not requested or a duplicate vertex" in {
    val a = researcherVertex(Ada)
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(a)))))
        .getResearcherMetricsBatch(Vector(Grace))
    }
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(a), row(a)))))
        .getResearcherMetricsBatch(Vector(Ada))
    }
  }

  // ---- ranking ----

  private def ranked(id: String, overrides: (String, String)*) =
    row(researcherVertex(id, overrides = overrides.toMap))

  "NeptuneStorageClient.topResearchersBy" should
    "rank by an allowlisted metric with window and limit as parameters" in {
      val transport = RecordingTransport(
        ok(results(ranked(Ada), ranked(Grace, K.DistinctCoauthorCount -> "5")))
      )
      val out = neptuneClient(transport).topResearchersBy("distinctCoauthorCount", window, 2)
      out.map(_.researcherId) shouldBe Vector(Ada, Grace)
      out.head shouldBe expectedAda

      val sent = form(transport.calls.head)
      sent("query") shouldBe
        "MATCH (r:Researcher)\n" +
        "WHERE r.windowStartYear = $ws AND r.windowEndYear = $we AND r.distinctCoauthorCount IS NOT NULL\n" +
        "RETURN r ORDER BY r.distinctCoauthorCount DESC, r.researcherId ASC LIMIT $lim"
      ujson.read(sent("parameters")) shouldBe ujson.Obj("ws" -> 2015, "we" -> 2024, "lim" -> 2)
    }

  it should "splice only allowlisted GraphSchema metric keys for every allowed metric" in {
    RankableResearcherMetric.AllowedNames shouldBe Vector(
      "distinctCoauthorCount",
      "publicationCount",
      "weightedDegree",
      "maximumCoauthorsOnOnePaper",
      "bridgeScore",
      "coreness"
    )
    for m <- RankableResearcherMetric.values do
      val (query, _) = NeptuneStorageClient.topResearchersQuery(m, window, 10)
      query should include(s"ORDER BY r.${m.propertyKey} DESC")
      K.ResearcherMetricKeys should contain(m.propertyKey)
      m.propertyKey shouldBe m.wireName
  }

  it should "reject a metric outside the allowlist before any request, without echoing it" in {
    for bad <- Vector(
        "totalCoauthorOccurrences",
        "DistinctCoauthorCount",
        "displayName",
        "coreness DESC //",
        "x) DETACH DELETE r //",
        ""
      )
    do
      val ex = intercept[IllegalArgumentException] {
        neptuneClient(NoNetwork).topResearchersBy(bad, window, 10)
      }
      ex.getMessage should include("unsupported ranking metric")
      if bad.nonEmpty then ex.getMessage should not include bad
    RankableResearcherMetric.fromName("coreness") shouldBe Right(RankableResearcherMetric.Coreness)
    RankableResearcherMetric.fromName("Coreness").isLeft shouldBe true
  }

  it should "reject limits outside 1..100 before any request" in {
    for lim <- Vector(0, -1, 101, Int.MaxValue) do
      intercept[IllegalArgumentException] {
        neptuneClient(NoNetwork).topResearchersBy(RankableResearcherMetric.Coreness, window, lim)
      }
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    neptuneClient(transport).topResearchersBy(RankableResearcherMetric.Coreness, window, 100)
    ujson.read(form(transport.calls.head)("parameters"))("lim") shouldBe ujson.Num(100)
  }

  it should "exclude not-computed bridge scores in the query and client-side" in {
    val notComputed =
      ranked(Grace, K.BridgeScoreMethod -> q("not-computed"), K.BridgeScore -> "0.0")
    val transport = RecordingTransport(ok(results(ranked(Ada), notComputed)))
    val out =
      neptuneClient(transport).topResearchersBy(RankableResearcherMetric.BridgeScore, window, 10)
    out.map(_.researcherId) shouldBe Vector(Ada)

    val sent = form(transport.calls.head)
    sent("query") should include(
      "AND r.bridgeScore IS NOT NULL AND r.bridgeScoreMethod <> $excludedMethod"
    )
    sent("query") should not include "not-computed"
    ujson.read(sent("parameters"))("excludedMethod") shouldBe ujson.Str("not-computed")
  }

  it should "not filter bridgeScoreMethod for other metrics" in {
    val notComputed =
      ranked(Grace, K.BridgeScoreMethod -> q("not-computed"), K.BridgeScore -> "0.0")
    val transport = RecordingTransport(ok(results(ranked(Ada), notComputed)))
    neptuneClient(transport)
      .topResearchersBy(RankableResearcherMetric.Coreness, window, 10)
      .map(_.researcherId) shouldBe Vector(Ada, Grace)
    form(transport.calls.head)("query") should not include K.BridgeScoreMethod
  }

  it should "fail with a typed error when a ranked vertex is malformed or lacks metrics" in {
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(ranked(Ada, K.Coreness -> q("four"))))))
        .topResearchersBy(RankableResearcherMetric.Coreness, window, 10)
    }
    intercept[NeptuneResponseFormatException] {
      neptuneClient(RecordingTransport(ok(results(row(researcherVertex(Ada, Vector.empty))))))
        .topResearchersBy(RankableResearcherMetric.Coreness, window, 10)
    }
  }

  "The metrics queries" should "reference only GraphSchema labels and property keys" in {
    val PropertyAccessed = """\b[A-Za-z_]\w*\.([A-Za-z_]\w*)""".r
    val MapKeyToken      = """\{\s*([A-Za-z_]\w*)\s*:""".r
    val queries = Vector(
      NeptuneStorageClient.researcherMetricsQuery,
      NeptuneStorageClient.researcherMetricsBatchQuery
    ) ++ RankableResearcherMetric.values.map(m =>
      NeptuneStorageClient.topResearchersQuery(m, window, 10)._1
    )
    val keys = queries.flatMap { qt =>
      PropertyAccessed.findAllMatchIn(qt).map(_.group(1)) ++
        MapKeyToken.findAllMatchIn(qt).map(_.group(1))
    }.toSet
    keys should not be empty
    withClue(s"undeclared keys: ${keys -- K.All}") {
      keys.subsetOf(K.All.toSet) shouldBe true
    }
    queries.foreach(_ should include(s":${GraphSchema.VertexLabel.Researcher}"))
  }

  "MetricWindow" should "reject an inverted window" in {
    intercept[IllegalArgumentException](MetricWindow(2024, 2015))
  }
