package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.services.ServiceTestFakes.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/** `/v1/graph/nodes/{id}/neighbors` parameter validation (review finding N43). */
class GraphRoutesSpec extends AnyFlatSpec with Matchers:

  /** Records the edge types the route passes through. */
  final private class RecordingGraph extends ServiceGraphStore:
    val seen = mutable.ArrayBuffer.empty[Vector[String]]

    def getNeighbors(nodeId: String, edgeTypes: Vector[String], depth: Int, limit: Int) =
      seen.synchronized(seen += edgeTypes)
      Vector(GraphNode("n2", "Researcher", Map("displayName" -> "B"), prov))

  private def withGraph[A](graph: ServiceGraphStore)(f: String => A): A =
    val (wf, _, audit) = RuntimeComposer.composeStubs()
    withRoutes(Routes(wf, graph, audit, testConfig))(f)

  "GET neighbors" should "return 400 BAD_REQUEST, not 500, for an undeclared edge type" in {
    val graph = RecordingGraph()
    withGraph(graph) { base =>
      val r = get(base, "/v1/graph/nodes/n1/neighbors?edgeTypes=CITES")
      r.statusCode() shouldBe 400
      val err = read[ErrorResponse](r.body())
      err.error shouldBe "BAD_REQUEST"
      err.message should include("COAUTHORED_WITH")
      err.retryable shouldBe false
    }
    graph.seen shouldBe empty
  }

  it should "return 400 for a malformed edge type and for one bad type among good ones" in {
    val graph = RecordingGraph()
    withGraph(graph) { base =>
      get(base, "/v1/graph/nodes/n1/neighbors?edgeTypes=COAUTHORED_WITH%5D-(x)")
        .statusCode() shouldBe 400
      get(base, "/v1/graph/nodes/n1/neighbors?edgeTypes=AUTHORED,coauthored_with")
        .statusCode() shouldBe 400
    }
    graph.seen shouldBe empty
  }

  it should "accept declared edge types and pass them through" in {
    val graph = RecordingGraph()
    withGraph(graph) { base =>
      val r = get(base, "/v1/graph/nodes/n1/neighbors?edgeTypes=COAUTHORED_WITH,AUTHORED&depth=2")
      r.statusCode() shouldBe 200
      read[Vector[NeighborResponse]](r.body()).map(_.nodeId) shouldBe Vector("n2")
    }
    graph.seen shouldBe mutable.ArrayBuffer(Vector("COAUTHORED_WITH", "AUTHORED"))
  }

  it should "return 400 for non-integer depth or limit" in {
    withGraph(RecordingGraph()) { base =>
      get(base, "/v1/graph/nodes/n1/neighbors?depth=two").statusCode() shouldBe 400
      get(base, "/v1/graph/nodes/n1/neighbors?limit=1e3").statusCode() shouldBe 400
    }
  }

  "NeptuneServiceGraphStore" should "restrict an empty edge-type list to coauthorship" in {
    val forms = mutable.ArrayBuffer.empty[String]
    val t = FakeTransport { call =>
      forms += URLDecoder.decode(call.bodyString, StandardCharsets.UTF_8)
      json(200, ujson.Obj("results" -> ujson.Arr()))
    }
    val g = NeptuneServiceGraphStore(neptune(t))
    g.getNeighbors("n1", Vector.empty, 2, 10) shouldBe empty
    g.getNeighbors("n1", Vector("AUTHORED"), 1, 10)
    forms(0) should include(":COAUTHORED_WITH*1..2]")
    forms(1) should include(":AUTHORED*1..1]")
  }
