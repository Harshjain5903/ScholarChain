package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.sql.DataFrame

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Producer-side contract for review finding N36: the Neptune bulk-load export uses only
 * [[GraphSchema]] labels, relationship types and property keys, and supplies every key the storage
 * openCypher queries match on.
 *
 * Expected key sets are derived from [[GraphSchema]] (`VertexKeys`, `ResearcherDescriptiveKeys`,
 * `ResearcherMetricKeys`, `WorkDescriptiveKeys`, `CoauthorEdgeKeys`, `Provenance`), not
 * hand-copied, so a schema change that the export does not follow fails here. The spark module
 * cannot depend on storage (module boundaries), so a direct cross-module assertion against the
 * storage queries belongs in the `tests/contracts` tree.
 */
class NeptuneBulkLoadExportSpec extends SparkTestBase:

  private val Label = GraphSchema.VertexLabel
  private val Rel   = GraphSchema.EdgeType
  private val Key   = GraphSchema.PropertyKey
  private val N     = NeptuneBulkLoadExport

  /**
   * Expected property keys per header, derived from GraphSchema: identity keys are the `VertexKeys`
   * that belong to the label (a Work has no researcher/component identity; a Researcher has no work
   * identity), plus the label's descriptive keys, the Researcher metric keys and `provenance`.
   */
  private object Expected:

    private val workOnly: Set[String]       = Set(Key.WorkId)
    private val researcherOnly: Set[String] = Set(Key.ResearcherId, Key.ComponentId)

    val researcherKeys: Set[String] =
      Key.VertexKeys.toSet.diff(workOnly) ++ Key.ResearcherDescriptiveKeys ++
        Key.ResearcherMetricKeys + Key.Provenance

    /** The Researcher header columns, in order, derived from GraphSchema. */
    val researcherColumnKeys: Vector[String] =
      Vector(Key.NodeId, Key.ResearcherId) ++ Key.ResearcherMetricKeys ++
        Key.ResearcherDescriptiveKeys :+ Key.Provenance

    val workKeys: Set[String] =
      Key.VertexKeys.toSet.diff(researcherOnly) ++ Key.WorkDescriptiveKeys + Key.Provenance
    val coauthorKeys: Set[String] = Key.CoauthorEdgeKeys.toSet
    val authoredKeys: Set[String] = Set(Key.Provenance)

  private def keysOf(header: Vector[String]): Set[String] =
    header.filterNot(N.System.All.contains).map(N.propertyKeyOf).toSet

  private val allHeaders = Seq(
    N.ResearcherNodeHeader,
    N.WorkNodeHeader,
    N.CoauthorRelationshipHeader,
    N.AuthoredRelationshipHeader
  )

  test("every exported property key is declared in GraphSchema.PropertyKey") {
    allHeaders.foreach { h =>
      keysOf(h).subsetOf(Key.All.toSet) shouldBe true
      h.filter(_.startsWith(":")).toSet.subsetOf(N.System.All) shouldBe true
    }
  }

  test("exported headers carry exactly the GraphSchema-derived key sets") {
    keysOf(N.ResearcherNodeHeader) shouldBe Expected.researcherKeys
    keysOf(N.WorkNodeHeader) shouldBe Expected.workKeys
    keysOf(N.CoauthorRelationshipHeader) shouldBe Expected.coauthorKeys
    keysOf(N.AuthoredRelationshipHeader) shouldBe Expected.authoredKeys
    // Every declared descriptive key is exported; year is typed so Neptune stores an integer.
    Key.ResearcherDescriptiveKeys.foreach(k => keysOf(N.ResearcherNodeHeader) should contain(k))
    Key.WorkDescriptiveKeys.foreach(k => keysOf(N.WorkNodeHeader) should contain(k))
    N.WorkNodeHeader should contain(s"${Key.Year}:Int")
    N.CoauthorRelationshipHeader should contain(s"${Key.JointPublicationCount}:Int")
    N.CoauthorRelationshipHeader.take(4) shouldBe Vector(":ID", ":START_ID", ":END_ID", ":TYPE")
    N.ResearcherNodeHeader.take(2) shouldBe Vector(":ID", ":LABEL")
    N.WorkNodeHeader.take(2) shouldBe Vector(":ID", ":LABEL")
  }

  // ---- A small export, read back.

  private val ts               = "2026-11-23T00:00:00Z"
  private def prov(id: String) = TestFixtures.mkProv(id)

  private val lineage = Lineage(
    "spark-run",
    "1.0.0",
    ts,
    sourceDataset = "dblp",
    upstreamRunIds = Vector("er-run-1", "hadoop-run-1"),
    policyVersion = Some("er-policy-1")
  )

  /** A JSON work ID containing a comma and quotes (needs CSV quoting and JSON escaping). */
  private val trickyWorkId = "work:conf/a,b \"x\""

  /**
   * Metrics covering every value shape: R1 has M_r ties (several maximum-team works, one with a
   * comma), a non-terminating average, sampled bridges and every option present; R2 has every
   * option absent, M_r = 0 and a scientific-notation bridge score; R3 has not-computed bridges.
   */
  private lazy val exportedMetrics: Seq[ResearcherMetrics] = Seq(
    ResearcherMetrics(
      researcherId = "R1",
      publicationCount = 3,
      distinctCoauthorCount = 2,
      totalCoauthorOccurrences = 4,
      averageCoauthorsPerPaper = 4.0 / 3.0,
      maximumCoauthorsOnOnePaper = 2,
      weightedDegree = 3.0,
      componentId = Some("component:R1"),
      communityId = Some("community:R1"),
      bridgeScore = 0.125,
      coreness = 2,
      windowStartYear = 2000,
      windowEndYear = 2030,
      provenance = prov("R1"),
      maximumTeamWorkIds = Vector("work:w1", "work:w3", trickyWorkId),
      bridgeScoreMethod = BridgeScoreMethod.Sampled,
      componentSize = Some(3L)
    ),
    ResearcherMetrics(
      researcherId = "R2",
      publicationCount = 1,
      distinctCoauthorCount = 0,
      totalCoauthorOccurrences = 0,
      averageCoauthorsPerPaper = 0.0,
      maximumCoauthorsOnOnePaper = 0,
      weightedDegree = 0.0,
      componentId = Some("component:R1"),
      communityId = None,
      bridgeScore = 1.0e-4,
      coreness = 0,
      windowStartYear = 2000,
      windowEndYear = 2030,
      provenance = prov("R2, \"quoted\""),
      componentSize = None
    ),
    ResearcherMetrics(
      researcherId = "R3",
      publicationCount = 1,
      distinctCoauthorCount = 1,
      totalCoauthorOccurrences = 1,
      averageCoauthorsPerPaper = 1.0,
      maximumCoauthorsOnOnePaper = 1,
      weightedDegree = 1.0,
      componentId = Some("component:R1"),
      communityId = Some("community:R1"),
      bridgeScore = 0.0,
      coreness = 1,
      windowStartYear = 2000,
      windowEndYear = 2030,
      provenance = prov("R3"),
      maximumTeamWorkIds = Vector("work:w2"),
      bridgeScoreMethod = BridgeScoreMethod.NotComputed,
      componentSize = Some(5000000000L)
    )
  )

  private lazy val outDir: Path =
    val dir     = Files.createTempDirectory("neptune-export")
    val metrics = sc.parallelize(exportedMetrics)
    val edges = sc.parallelize(
      Seq(
        CoauthorEdge("R1", "R2", 2, 2019, 2020, Vector("work:w1", "work:w2"), prov("e1")),
        CoauthorEdge("R2", "R3", 1, 2020, 2020, Vector("work:w2"), prov("e2, \"quoted\""))
      )
    )
    val works = sc.parallelize(
      Seq(
        N.WorkNode("work:w1", prov("w1"), Some("A \"quoted\", title"), Some(2019), Some("3dv")),
        N.WorkNode("work:w2", prov("w2"), None, Some(2020), None)
      )
    )
    // R9 has a display name but no metrics (outside the window): it must be excluded.
    val names = sc.parallelize(
      Seq("R1" -> "Ada Lovelace", "R2" -> "Alan Turing", "R9" -> "Outside Window")
    )
    val authorships = sc.parallelize(
      Seq(
        "R1" -> "work:w1",
        "R2" -> "work:w1",
        "R1" -> "work:w2",
        "R2" -> "work:w2",
        "R3" -> "work:w2",
        "R3" -> "work:w2"
      )
    )
    val counts = N.writeAll(
      spark,
      dir.toUri.toString,
      metrics,
      names,
      works,
      edges,
      authorships,
      2000,
      2030,
      "v1",
      lineage
    )
    counts shouldBe N.Counts(3, 2, 2, 5)
    dir

  private def read(subdir: String): DataFrame =
    spark.read
      .option("header", "true")
      .option("quote", "\"")
      .option("escape", "\"")
      .csv(outDir.resolve(subdir).toString)

  private def partFiles(subdir: String): Seq[Path] =
    Files
      .list(outDir.resolve(subdir))
      .iterator()
      .asScala
      .toSeq
      .filter(_.getFileName.toString.startsWith("part-"))

  /**
   * Minimal RFC 4180 parser, independent of Spark, standing in for the Neptune loader: fields are
   * separated by commas, a quoted field may contain commas, newlines and doubled quotes. An empty
   * unquoted field is `None` (Neptune: property not set); a quoted field is always `Some`.
   */
  private def parseCsv(text: String): Vector[Vector[Option[String]]] =
    val records = Vector.newBuilder[Vector[Option[String]]]
    var fields  = Vector.newBuilder[Option[String]]
    val field   = new StringBuilder
    var quoted  = false
    var inQuote = false
    var i       = 0
    def endField(): Unit =
      fields += (if quoted || field.nonEmpty then Some(field.toString) else None)
      field.clear(); quoted = false
    while i < text.length do
      val c = text.charAt(i)
      if inQuote then
        if c == '"' then
          if i + 1 < text.length && text.charAt(i + 1) == '"' then
            field += '"'; i += 1
          else inQuote = false
        else field += c
      else
        c match
          case '"'  => inQuote = true; quoted = true
          case ','  => endField()
          case '\r' => ()
          case '\n' =>
            endField(); records += fields.result(); fields = Vector.newBuilder
          case other => field += other
      i += 1
    if field.nonEmpty || quoted then
      endField(); records += fields.result()
    records.result()

  /** Researcher node rows from the raw part files: header -> cell (None = empty unquoted cell). */
  private lazy val rawResearcherRows: Seq[Map[String, Option[String]]] =
    partFiles(N.Dir.ResearcherNodes).filter(f => Files.size(f) > 0).flatMap { f =>
      val records = parseCsv(Files.readString(f))
      val header  = records.head.map(_.get)
      header shouldBe N.ResearcherNodeHeader
      records.tail.map { r =>
        r should have size header.size.toLong
        header.zip(r).toMap
      }
    }

  /** Decode one raw row the way a reader of the loaded vertex would. */
  private def decode(row: Map[String, Option[String]]): Either[String, ResearcherMetrics] =
    val properties = row.collect {
      case (h, Some(v)) if !N.System.All.contains(h) => N.propertyKeyOf(h) -> v
    }
    ResearcherMetrics.fromGraphProperties(
      row(N.System.Id).get,
      properties,
      upickle.default.read[Provenance](properties(Key.Provenance))
    )

  test("Researcher header has one column per GraphSchema metric key, in GraphSchema order") {
    N.ResearcherNodeHeader
      .filterNot(N.System.All.contains)
      .map(N.propertyKeyOf) shouldBe Expected.researcherColumnKeys
    N.ResearcherNodeHeader.distinct shouldBe N.ResearcherNodeHeader
    Key.ResearcherMetricKeys.foreach { k =>
      N.ResearcherNodeHeader.count(h => N.propertyKeyOf(h) == k) shouldBe 1
    }
  }

  test("Researcher metric columns carry the openCypher bulk-load type suffix") {
    val typeOf = N.ResearcherNodeHeader
      .filterNot(N.System.All.contains)
      .map(h => N.propertyKeyOf(h) -> h.drop(N.propertyKeyOf(h).length + 1))
      .toMap
    Seq(
      Key.PublicationCount,
      Key.DistinctCoauthorCount,
      Key.TotalCoauthorOccurrences,
      Key.MaximumCoauthorsOnOnePaper,
      Key.Coreness,
      Key.WindowStartYear,
      Key.WindowEndYear
    ).foreach(k => typeOf(k) shouldBe "Int")
    typeOf(Key.ComponentSize) shouldBe "Long"
    Seq(Key.AverageCoauthorsPerPaper, Key.WeightedDegree, Key.BridgeScore).foreach(k =>
      typeOf(k) shouldBe "Double"
    )
    Seq(Key.ComponentId, Key.CommunityId, Key.BridgeScoreMethod, Key.MaximumTeamWorkIds).foreach(
      k => typeOf(k) shouldBe "String"
    )
    // Every metric key is typed; the type table and GraphSchema agree exactly.
    N.ResearcherMetricTypes.keySet shouldBe Key.ResearcherMetricKeys.toSet
  }

  test("round-trip: export, parse the CSV row, fromGraphProperties equals the original metrics") {
    val rows = rawResearcherRows.map(r => r(N.System.Id).get -> r).toMap
    rows.keySet shouldBe exportedMetrics.map(_.researcherId).toSet
    exportedMetrics.foreach { m =>
      decode(rows(m.researcherId)) shouldBe Right(m)
    }
    // R1: M_r ties are one JSON-array string, including the work ID with a comma and quotes.
    val r1Ids = rows("R1")(N.property(Key.MaximumTeamWorkIds)).get
    upickle.default.read[Vector[String]](r1Ids) shouldBe Vector("work:w1", "work:w3", trickyWorkId)
    rows("R1")(N.property(Key.ComponentSize, "Long")) shouldBe Some("3")
    rows("R3")(N.property(Key.ComponentSize, "Long")) shouldBe Some("5000000000")
    // The same rows read back through Spark's CSV reader (as other consumers do) also decode.
    read(N.Dir.ResearcherNodes).collect().foreach { r =>
      val row = r.schema.fieldNames.map(h => h -> Option(r.getAs[String](h))).toMap
      decode(row).map(_.researcherId) shouldBe Right(row(N.System.Id).get)
    }
  }

  test("absent options are empty unquoted cells, and quoting follows RFC 4180") {
    val r2 = rawResearcherRows.find(_(N.System.Id).contains("R2")).get
    r2(N.property(Key.CommunityId)) shouldBe None
    r2(N.property(Key.ComponentSize, "Long")) shouldBe None
    r2(N.property(Key.MaximumTeamWorkIds)) shouldBe Some("[]")
    r2(N.property(Key.BridgeScore, "Double")) shouldBe Some("1.0E-4")
    val raw = partFiles(N.Dir.ResearcherNodes).map(f => Files.readString(f)).mkString
    // The JSON array is one quoted CSV field: CSV doubles the quotes, JSON escapes the inner ones.
    raw should include("\"[\"\"work:w1\"\",\"\"work:w3\"\",\"\"work:conf/a,b \\\"\"x\\\"\"\"\"]\"")
    // R2's row has bare empty fields for communityId and componentSize (no quoted "").
    val r2Line = raw.linesIterator.find(_.startsWith("R2,")).get
    r2Line should include(",component:R1,,")
    r2Line should not include ",\"\","
  }

  test("researchers without metrics are excluded and every exported row decodes") {
    rawResearcherRows.map(_(N.System.Id).get) should not contain "R9"
    rawResearcherRows.foreach(r => decode(r).isRight shouldBe true)
    // No exported row has an empty cell for a key the reader requires.
    val requiredKeys = Key.ResearcherMetricKeys.filterNot(
      Set(Key.ComponentId, Key.ComponentSize, Key.CommunityId, Key.MaximumTeamWorkIds)
    )
    rawResearcherRows.foreach { r =>
      requiredKeys.foreach(k => r(N.property(k, N.researcherMetricType(k))) shouldBe defined)
    }
  }

  test("written labels and relationship types are GraphSchema values") {
    read(N.Dir.ResearcherNodes).select(":LABEL").collect().map(_.getString(0)).toSet shouldBe Set(
      Label.Researcher
    )
    read(N.Dir.WorkNodes).select(":LABEL").collect().map(_.getString(0)).toSet shouldBe Set(
      Label.Work
    )
    read(N.Dir.CoauthorRelationships)
      .select(":TYPE")
      .collect()
      .map(_.getString(0))
      .toSet shouldBe Set(Rel.Coauthor)
    read(N.Dir.AuthoredRelationships)
      .select(":TYPE")
      .collect()
      .map(_.getString(0))
      .toSet shouldBe Set(Rel.Authored)
  }

  test("each non-empty part file starts with the exact header row") {
    Seq(
      N.Dir.ResearcherNodes       -> N.ResearcherNodeHeader,
      N.Dir.WorkNodes             -> N.WorkNodeHeader,
      N.Dir.CoauthorRelationships -> N.CoauthorRelationshipHeader,
      N.Dir.AuthoredRelationships -> N.AuthoredRelationshipHeader
    ).foreach { case (dir, header) =>
      val files = partFiles(dir).filter(f => Files.size(f) > 0)
      files should not be empty
      files.foreach(f => Files.readAllLines(f).get(0) shouldBe header.mkString(","))
    }
  }

  test("relationship endpoints exist as nodes and IDs are unique") {
    val researchers = read(N.Dir.ResearcherNodes).select(":ID").collect().map(_.getString(0)).toSeq
    val works       = read(N.Dir.WorkNodes).select(":ID").collect().map(_.getString(0)).toSeq
    researchers.distinct.size shouldBe researchers.size
    researchers.toSet shouldBe Set("R1", "R2", "R3")
    val coauthor = read(N.Dir.CoauthorRelationships).collect()
    coauthor.foreach { r =>
      researchers should contain(r.getAs[String](":START_ID"))
      researchers should contain(r.getAs[String](":END_ID"))
      r.getAs[String](":START_ID") should be < r.getAs[String](":END_ID")
    }
    coauthor.map(_.getAs[String](":ID")).toSet shouldBe Set(
      IdGen.coauthorEdgeId("R1", "R2", 2000, 2030, "v1"),
      IdGen.coauthorEdgeId("R2", "R3", 2000, 2030, "v1")
    )
    val authored = read(N.Dir.AuthoredRelationships).collect()
    authored.foreach { r =>
      researchers should contain(r.getAs[String](":START_ID"))
      works should contain(r.getAs[String](":END_ID"))
    }
    authored.map(_.getAs[String](":ID")).distinct should have size 5
  }

  test("node identity properties equal :ID and componentId is carried for storage lookups") {
    read(N.Dir.ResearcherNodes).collect().foreach { r =>
      val id = r.getAs[String](":ID")
      r.getAs[String](s"${Key.NodeId}:String") shouldBe id
      r.getAs[String](s"${Key.ResearcherId}:String") shouldBe id
      r.getAs[String](s"${Key.ComponentId}:String") shouldBe "component:R1"
    }
    read(N.Dir.WorkNodes).collect().foreach { r =>
      r.getAs[String](s"${Key.WorkId}:String") shouldBe r.getAs[String](":ID")
    }
  }

  test("JSON-encoded properties round-trip, with RFC 4180 quoting of embedded quotes") {
    val rows =
      read(N.Dir.CoauthorRelationships).collect().map(r => r.getAs[String](":START_ID") -> r).toMap
    val r23 = rows("R2")
    upickle.default
      .read[Provenance](r23.getAs[String](s"${Key.Provenance}:String"))
      .sourceRecordId shouldBe
      "e2, \"quoted\""
    upickle.default.read[Vector[String]](
      rows("R1").getAs[String](s"${Key.RepresentativeWorkIds}:String")
    ) shouldBe
      Vector("work:w1", "work:w2")
    rows("R1").getAs[String](s"${Key.JointPublicationCount}:Int") shouldBe "2"
    val raw = partFiles(N.Dir.CoauthorRelationships).map(f => Files.readString(f)).mkString
    // CSV-level quoting doubles quotes (RFC 4180); the backslash before a quote inside the
    // provenance value is JSON's own escaping and survives unchanged.
    raw should include("\"{\"\"sourceDataset\"\":")
  }

  test("no _SUCCESS marker is written under the Neptune prefix") {
    Files
      .walk(outDir)
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .toSeq should not contain "_SUCCESS"
  }

  test("manifest points the neptune target at the openCypher CSV prefixes") {
    val dir = Files.createTempDirectory("neptune-manifest").toUri.toString.stripSuffix("/")
    GraphExportWriter.writeManifest(spark, dir, "run-1", ts, neptuneCsv = true)
    val json    = ujson.read(sc.textFile(s"$dir/manifest").collect().mkString)
    val neptune = json("targets")("neptune")
    neptune("format").str shouldBe "opencypher"
    neptune("nodes").str shouldBe s"$dir/${N.Dir.Nodes}"
    neptune("loadOrder").arr.map(_.str).toSeq shouldBe Seq(
      s"$dir/${N.Dir.Nodes}",
      s"$dir/${N.Dir.Relationships}"
    )
    neptune("vertexLabels").arr.map(_.str).toSet shouldBe Label.All.toSet
    neptune("relationshipTypes").arr.map(_.str).toSet shouldBe Rel.All.toSet
  }

  test("edges Parquet columns use GraphSchema property keys") {
    val dir = Files.createTempDirectory("edges-parquet").toString
    GraphExportWriter.writeEdges(spark, sc.parallelize(TestFixtures.allEdges), dir)
    spark.read
      .parquet(s"$dir/edges")
      .columns
      .toSet should contain allElementsOf Key.CoauthorEdgeKeys
  }

  test("relationship type values come from GraphSchema: coauthor edges are COAUTHORED_WITH") {
    Rel.Coauthor shouldBe "COAUTHORED_WITH"
    val raw = partFiles(N.Dir.CoauthorRelationships).map(f => Files.readString(f)).mkString
    raw should include(s",${Rel.Coauthor},")
    raw should not include ",COAUTHOR,"
  }

  test("descriptive properties are written where known and left empty (null) where unknown") {
    val researchers = read(N.Dir.ResearcherNodes)
      .collect()
      .map(r => r.getAs[String](":ID") -> r.getAs[String](s"${Key.DisplayName}:String"))
      .toMap
    researchers shouldBe Map("R1" -> "Ada Lovelace", "R2" -> "Alan Turing", "R3" -> null)
    val works = read(N.Dir.WorkNodes).collect().map(r => r.getAs[String](":ID") -> r).toMap
    works("work:w1").getAs[String](s"${Key.Title}:String") shouldBe "A \"quoted\", title"
    works("work:w1").getAs[String](s"${Key.Year}:Int") shouldBe "2019"
    works("work:w1").getAs[String](s"${Key.Venue}:String") shouldBe "3dv"
    works("work:w2").getAs[String](s"${Key.Title}:String") shouldBe null
    works("work:w2").getAs[String](s"${Key.Venue}:String") shouldBe null
    works("work:w2").getAs[String](s"${Key.Year}:Int") shouldBe "2020"
    // Unknown values are bare empty fields, not quoted empty strings.
    val raw = partFiles(N.Dir.WorkNodes).map(f => Files.readString(f)).mkString
    raw should include("work:w2,Work,work:w2,work:w2,,2020,,")
  }

  test("AUTHORED provenance carries the run lineage, not a hardcoded dataset") {
    val p = upickle.default.read[Provenance](
      read(N.Dir.AuthoredRelationships).collect().head.getAs[String](s"${Key.Provenance}:String")
    )
    p.sourceDataset shouldBe "dblp"
    p.pipelineRunId shouldBe "spark-run"
    p.transformationStep shouldBe Some("authorship-export")
    p.producer shouldBe Some("spark-analytics/NeptuneBulkLoadExport")
    p.upstreamRunIds shouldBe Vector("er-run-1", "hadoop-run-1")
    p.policyVersion shouldBe Some("er-policy-1")
  }
