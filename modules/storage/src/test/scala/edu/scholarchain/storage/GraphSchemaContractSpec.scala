package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Phase 12 repair-07 (N36): the Neptune openCypher queries and response parsing take every vertex
 * label, edge type and property key from [[GraphSchema]]. No AWS calls: fake transports only.
 */
class GraphSchemaContractSpec extends AnyFlatSpec with Matchers:

  import StorageTestAws.*

  private val testProvenance = Provenance(
    sourceDataset = "test-dataset",
    sourceRecordId = "rec-001",
    pipelineRunId = "run-001",
    schemaVersion = "1.0.0",
    timestamp = "2026-01-01T00:00:00Z"
  )

  private def neptuneClient(transport: HttpTransport) =
    NeptuneStorageClient(
      endpoint = "scholarchain-dev.cluster-abc.us-east-1.neptune.amazonaws.com",
      port = 8182,
      region = "us-east-1",
      queryTimeoutMs = 5000,
      transport = transport,
      credentialsProvider = dummyCredentials,
      clock = fixedClock
    )

  private def sentQuery(call: HttpCall): String =
    call.bodyString
      .split("&")
      .collectFirst { case kv if kv.startsWith("query=") => kv.stripPrefix("query=") }
      .map(URLDecoder.decode(_, StandardCharsets.UTF_8))
      .get

  /** Every query text the client sends, captured from the wire for each public method. */
  private def allSentQueries: Vector[String] =
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    val client    = neptuneClient(transport)
    client.getResearcher("r1")
    client.getWork("w1")
    client.getDirectCoauthors("r1", 10)
    client.shortestPath("r1", "r2", 4)
    client.shortestPath("r1", "r2", 4, GraphSchema.EdgeType.All)
    client.getNeighbors("n1", GraphSchema.EdgeType.All, 2, 10)
    client.getNeighbors("n1", Vector.empty, 2, 10)
    client.getComponentMembers("c1", 10)
    transport.calls.toVector.map(sentQuery)

  private val NodeLabelToken   = """\(\s*\w*\s*:\s*([A-Za-z_]\w*)""".r
  private val RelTypeToken     = """\[\s*\w*\s*:\s*([A-Za-z_][\w|]*)""".r
  private val MapKeyToken      = """\{\s*([A-Za-z_]\w*)\s*:""".r
  private val PropertyAccessed = """\b[A-Za-z_]\w*\.([A-Za-z_]\w*)""".r

  private def labelsIn(q: String)    = NodeLabelToken.findAllMatchIn(q).map(_.group(1)).toSet
  private def edgeTypesIn(q: String) =
    RelTypeToken.findAllMatchIn(q).flatMap(_.group(1).split('|')).toSet
  private def keysIn(q: String) =
    MapKeyToken.findAllMatchIn(q).map(_.group(1)).toSet ++
      PropertyAccessed.findAllMatchIn(q).map(_.group(1)).toSet

  "NeptuneStorageClient queries" should
    "reference only GraphSchema vertex labels, edge types and property keys" in {
      val queries = allSentQueries
      queries should have size 8
      val labels    = queries.flatMap(labelsIn).toSet
      val edgeTypes = queries.flatMap(edgeTypesIn).toSet
      val keys      = queries.flatMap(keysIn).toSet

      // The token scan must actually find things, or the subset checks below are vacuous.
      labels shouldBe Set(GraphSchema.VertexLabel.Researcher, GraphSchema.VertexLabel.Work)
      edgeTypes shouldBe GraphSchema.EdgeType.All.toSet
      keys should not be empty

      labels.subsetOf(GraphSchema.VertexLabel.All.toSet) shouldBe true
      edgeTypes.subsetOf(GraphSchema.EdgeType.All.toSet) shouldBe true
      withClue(s"undeclared keys: ${keys -- GraphSchema.PropertyKey.All}") {
        keys.subsetOf(GraphSchema.PropertyKey.All.toSet) shouldBe true
      }
    }

  it should "keep the pre-N36 query text byte-identical" in {
    NeptuneStorageClient.getResearcherQuery shouldBe
      "MATCH (r:Researcher {researcherId: $rid}) RETURN r"
    NeptuneStorageClient.getWorkQuery shouldBe "MATCH (w:Work {workId: $wid}) RETURN w"
    NeptuneStorageClient.directCoauthorsQuery shouldBe
      "MATCH (r:Researcher {researcherId: $rid})-[e:COAUTHOR]-(other:Researcher)\n" +
      "RETURN e ORDER BY e.jointPublicationCount DESC LIMIT $lim"
    NeptuneStorageClient.componentMembersQuery shouldBe
      "MATCH (r:Researcher {componentId: $cid}) RETURN r.researcherId AS rid LIMIT $lim"
    NeptuneStorageClient.neighborsQuery(Vector("COAUTHOR"), 2) shouldBe
      "MATCH (start {nodeId: $nid})-[e:COAUTHOR*1..2]-(neighbor)\n" +
      "RETURN DISTINCT neighbor LIMIT $lim"
    NeptuneStorageClient.neighborsQuery(Vector.empty, 9) shouldBe
      "MATCH (start {nodeId: $nid})-[e*1..3]-(neighbor)\n" +
      "RETURN DISTINCT neighbor LIMIT $lim"
    NeptuneStorageClient.shortestPathQuery("a", "b", 4)._1 shouldBe
      "MATCH p = (a:Researcher {researcherId: $fromId})-[:COAUTHOR*1..4]-" +
      "(b:Researcher {researcherId: $toId})\n" +
      "WITH p ORDER BY length(p) ASC LIMIT 1\n" +
      "RETURN [n IN nodes(p) | n.researcherId] AS path"
  }

  it should "send exactly the published query constants" in {
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    val client    = neptuneClient(transport)
    client.getResearcher("r1")
    client.getWork("w1")
    client.getDirectCoauthors("r1", 10)
    client.getComponentMembers("c1", 10)
    client.getNeighbors("n1", Vector(GraphSchema.EdgeType.Coauthor), 2, 10)
    transport.calls.toVector.map(sentQuery) shouldBe Vector(
      NeptuneStorageClient.getResearcherQuery,
      NeptuneStorageClient.getWorkQuery,
      NeptuneStorageClient.directCoauthorsQuery,
      NeptuneStorageClient.componentMembersQuery,
      NeptuneStorageClient.neighborsQuery(Vector(GraphSchema.EdgeType.Coauthor), 2)
    )
  }

  "NeptuneStorageClient edge types" should "default path traversal to GraphSchema COAUTHOR" in {
    NeptuneStorageClient.DefaultPathEdgeTypes shouldBe Vector(GraphSchema.EdgeType.Coauthor)
  }

  it should "delegate identifier validation to GraphSchema.isValidEdgeTypeIdentifier" in {
    val samples =
      Vector("COAUTHOR", "AUTHORED", "AFFILIATED_WITH", "_x1", "1BAD", "A-B", "A]-()", "", "a b")
    samples.foreach { s =>
      NeptuneStorageClient.isValidEdgeType(s) shouldBe GraphSchema.isValidEdgeTypeIdentifier(s)
    }
  }

  it should "accept every declared edge type in the filter" in {
    NeptuneStorageClient.edgeTypeFilter(GraphSchema.EdgeType.All) shouldBe
      ":" + GraphSchema.EdgeType.All.mkString("|")
    NeptuneStorageClient.edgeTypeFilter(Vector.empty) shouldBe ""
  }

  it should "reject a well-formed but undeclared edge type in shortestPath before sending" in {
    val e = the[IllegalArgumentException] thrownBy
      neptuneClient(NoNetwork).shortestPath("a", "b", 3, Vector("AFFILIATED_WITH"))
    e.getMessage should include("undeclared edge type 'AFFILIATED_WITH'")
  }

  it should "reject a well-formed but undeclared edge type in getNeighbors before sending" in {
    an[IllegalArgumentException] should be thrownBy
      neptuneClient(NoNetwork).getNeighbors("n1", Vector("COAUTHOR", "CITES"), 1, 10)
  }

  it should "reject a wrong-case declared edge type before sending" in {
    an[IllegalArgumentException] should be thrownBy
      neptuneClient(NoNetwork).getNeighbors("n1", Vector("coauthor"), 1, 10)
    an[IllegalArgumentException] should be thrownBy
      NeptuneStorageClient.shortestPathQuery("a", "b", 3, Vector("Coauthor"))
  }

  // ---- Response parsing uses GraphSchema key names ----

  private val edge = CoauthorEdge(
    researcherA = "r1",
    researcherB = "r2",
    jointPublicationCount = 3,
    firstCollaborationYear = 2019,
    lastCollaborationYear = 2024,
    representativeWorkIds = Vector("w1", "w2"),
    provenance = testProvenance
  )

  private def edgeRow(keyRename: Map[String, String] = Map.empty): ujson.Value =
    val K = GraphSchema.PropertyKey
    val props = Vector[(String, ujson.Value)](
      K.ResearcherA            -> ujson.Str("r1"),
      K.ResearcherB            -> ujson.Str("r2"),
      K.JointPublicationCount  -> ujson.Num(3),
      K.FirstCollaborationYear -> ujson.Num(2019),
      K.LastCollaborationYear  -> ujson.Num(2024),
      K.RepresentativeWorkIds  -> ujson.Arr("w1", "w2"),
      K.Provenance             -> upickle.default.writeJs(testProvenance)
    ).map((k, v) => keyRename.getOrElse(k, k) -> v)
    ujson.Obj(
      "results" -> ujson.Arr(ujson.Obj(NeptuneResponseParser.Column.Edge -> ujson.Obj.from(props)))
    )

  "NeptuneResponseParser" should "parse a COAUTHOR edge stored under the GraphSchema keys" in {
    NeptuneResponseParser.parseCoauthorEdges(edgeRow()) shouldBe Vector(edge)
  }

  it should "decode records whose field names are exactly the GraphSchema keys" in {
    upickle.default.writeJs(edge).obj.keySet.toSet shouldBe
      GraphSchema.PropertyKey.CoauthorEdgeKeys.toSet
    val node = GraphNode("n1", GraphSchema.VertexLabel.Researcher, Map.empty, testProvenance)
    upickle.default.writeJs(node).obj.keySet should contain(GraphSchema.PropertyKey.NodeId)
    node.hasDeclaredVertexLabel shouldBe true
  }

  it should "fail on a COAUTHOR edge whose property uses a non-GraphSchema key" in {
    val renamed = edgeRow(Map(GraphSchema.PropertyKey.JointPublicationCount -> "joint_count"))
    an[Exception] should be thrownBy NeptuneResponseParser.parseCoauthorEdges(renamed)
  }

  it should "read result columns using the aliases the queries bind" in {
    val C = NeptuneResponseParser.Column
    NeptuneStorageClient.getResearcherQuery should endWith(s"RETURN ${C.Researcher}")
    NeptuneStorageClient.getWorkQuery should endWith(s"RETURN ${C.Work}")
    NeptuneStorageClient.directCoauthorsQuery should include(s"RETURN ${C.Edge} ")
    NeptuneStorageClient.componentMembersQuery should include(s" AS ${C.ResearcherIds} ")
    NeptuneStorageClient.neighborsQuery(Vector.empty, 1) should include(s"DISTINCT ${C.Neighbor} ")
    NeptuneStorageClient.shortestPathQuery("a", "b", 2)._1 should endWith(s" AS ${C.Path}")

    val members = ujson.Obj("results" -> ujson.Arr(ujson.Obj(C.ResearcherIds -> "r7")))
    val path    = ujson.Obj("results" -> ujson.Arr(ujson.Obj(C.Path -> ujson.Arr("r1", "r2"))))
    val transport = RecordingTransport(ok(ujson.write(members)), ok(ujson.write(path)))
    val client    = neptuneClient(transport)
    client.getComponentMembers("c1", 5) shouldBe Vector("r7")
    client.shortestPath("r1", "r2", 2) shouldBe Some(Vector("r1", "r2"))
  }
