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
 * The storage queries (`NeptuneStorageClient.getResearcherQuery`, `directCoauthorsQuery`,
 * `componentMembersQuery`, `neighborsQuery`, `shortestPathQuery`, `getWorkQuery`) are built from
 * the same `GraphSchema` constants; [[QueriedByStorage]] restates which (label, key) pairs and
 * relationship types they read. The spark module cannot depend on storage (module boundaries), so a
 * direct cross-module assertion belongs in the `tests/contracts` tree.
 */
class NeptuneBulkLoadExportSpec extends SparkTestBase:

  private val Label = GraphSchema.VertexLabel
  private val Rel   = GraphSchema.EdgeType
  private val Key   = GraphSchema.PropertyKey
  private val N     = NeptuneBulkLoadExport

  /** (vertex label -> property keys) and relationship types read by the storage queries. */
  private object QueriedByStorage:

    val vertexKeys: Map[String, Set[String]] = Map(
      Label.Researcher -> Set(Key.ResearcherId, Key.ComponentId, Key.NodeId),
      Label.Work       -> Set(Key.WorkId, Key.NodeId)
    )
    val coauthorKeys: Set[String]      = Key.CoauthorEdgeKeys.toSet
    val relationshipTypes: Set[String] = Set(Rel.Coauthor)

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

  test("exported headers cover every key the storage queries and parser read") {
    QueriedByStorage
      .vertexKeys(Label.Researcher)
      .subsetOf(keysOf(N.ResearcherNodeHeader)) shouldBe true
    QueriedByStorage.vertexKeys(Label.Work).subsetOf(keysOf(N.WorkNodeHeader)) shouldBe true
    keysOf(N.CoauthorRelationshipHeader) shouldBe QueriedByStorage.coauthorKeys
    N.CoauthorRelationshipHeader should contain(s"${Key.JointPublicationCount}:Int")
    N.CoauthorRelationshipHeader.take(4) shouldBe Vector(":ID", ":START_ID", ":END_ID", ":TYPE")
    N.ResearcherNodeHeader.take(2) shouldBe Vector(":ID", ":LABEL")
    N.WorkNodeHeader.take(2) shouldBe Vector(":ID", ":LABEL")
  }

  // ---- A small export, read back.

  private val ts               = "2026-11-23T00:00:00Z"
  private def prov(id: String) = TestFixtures.mkProv(id)

  private lazy val outDir: Path =
    val dir = Files.createTempDirectory("neptune-export")
    val metrics = sc.parallelize(
      Seq("R1", "R2", "R3").map(r =>
        ResearcherMetrics(
          r,
          1,
          1,
          1,
          1.0,
          1,
          1.0,
          Some("component:R1"),
          None,
          0.0,
          1,
          2000,
          2030,
          prov(r)
        )
      )
    )
    val edges = sc.parallelize(
      Seq(
        CoauthorEdge("R1", "R2", 2, 2019, 2020, Vector("work:w1", "work:w2"), prov("e1")),
        CoauthorEdge("R2", "R3", 1, 2020, 2020, Vector("work:w2"), prov("e2, \"quoted\""))
      )
    )
    val works = sc.parallelize(Seq("work:w1" -> prov("w1"), "work:w2" -> prov("w2")))
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
      works,
      edges,
      authorships,
      2000,
      2030,
      "v1",
      "run",
      "1.0.0",
      ts
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
