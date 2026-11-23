package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/**
 * Contract tests for [[GraphSchema]] (review finding N36). The names are a Neptune wire contract
 * shared by Spark export, loaders and storage queries: changing any value must fail here.
 */
class GraphSchemaSpec extends AnyFlatSpec with Matchers {

  import GraphSchema.*
  import TestFixtures.*

  "GraphSchema.VertexLabel" should "pin the exact vertex label values" in {
    VertexLabel.Researcher shouldBe "Researcher"
    VertexLabel.Work shouldBe "Work"
    VertexLabel.All shouldBe Vector("Researcher", "Work")
  }

  "GraphSchema.EdgeType" should "pin the exact edge type values" in {
    EdgeType.Coauthor shouldBe "COAUTHOR"
    EdgeType.Authored shouldBe "AUTHORED"
    EdgeType.All shouldBe Vector("COAUTHOR", "AUTHORED")
  }

  "GraphSchema.PropertyKey" should "pin the exact vertex key values" in {
    PropertyKey.NodeId shouldBe "nodeId"
    PropertyKey.ResearcherId shouldBe "researcherId"
    PropertyKey.ComponentId shouldBe "componentId"
    PropertyKey.WorkId shouldBe "workId"
    PropertyKey.VertexKeys shouldBe Vector("nodeId", "researcherId", "componentId", "workId")
  }

  it should "pin the exact COAUTHOR edge key values" in {
    PropertyKey.ResearcherA shouldBe "researcherA"
    PropertyKey.ResearcherB shouldBe "researcherB"
    PropertyKey.JointPublicationCount shouldBe "jointPublicationCount"
    PropertyKey.FirstCollaborationYear shouldBe "firstCollaborationYear"
    PropertyKey.LastCollaborationYear shouldBe "lastCollaborationYear"
    PropertyKey.RepresentativeWorkIds shouldBe "representativeWorkIds"
    PropertyKey.Provenance shouldBe "provenance"
    PropertyKey.CoauthorEdgeKeys shouldBe Vector(
      "researcherA",
      "researcherB",
      "jointPublicationCount",
      "firstCollaborationYear",
      "lastCollaborationYear",
      "representativeWorkIds",
      "provenance"
    )
  }

  it should "list every declared key exactly once in All" in {
    PropertyKey.All shouldBe (PropertyKey.VertexKeys ++ PropertyKey.CoauthorEdgeKeys)
    PropertyKey.All.size shouldBe 11
  }

  "GraphSchema declared names" should "be unique within each category" in {
    VertexLabel.All.distinct shouldBe VertexLabel.All
    EdgeType.All.distinct shouldBe EdgeType.All
    PropertyKey.VertexKeys.distinct shouldBe PropertyKey.VertexKeys
    PropertyKey.CoauthorEdgeKeys.distinct shouldBe PropertyKey.CoauthorEdgeKeys
  }

  it should "not collide between vertex labels and edge types (case-insensitively)" in {
    val labels = VertexLabel.All.map(_.toLowerCase).toSet
    val edges  = EdgeType.All.map(_.toLowerCase).toSet
    (labels intersect edges) shouldBe empty
    (labels ++ edges).size shouldBe VertexLabel.All.size + EdgeType.All.size
  }

  it should "all be plain identifiers safe to place in openCypher query text" in {
    (VertexLabel.All ++ EdgeType.All ++ PropertyKey.All).foreach { name =>
      withClue(s"name '$name': ") {
        isValidEdgeTypeIdentifier(name) shouldBe true
      }
    }
  }

  "GraphSchema property keys" should "match the JSON field names of the core records they mirror" in {
    val coauthorFields = writeJs(sampleCoauthorEdge).obj.keySet.toSet
    coauthorFields shouldBe PropertyKey.CoauthorEdgeKeys.toSet

    writeJs(sampleCanonicalResearcher).obj.keySet should contain(PropertyKey.ResearcherId)
    writeJs(sampleWorkRecord).obj.keySet should contain(PropertyKey.WorkId)
    writeJs(sampleGraphNode).obj.keySet should contain(PropertyKey.NodeId)
    writeJs(sampleResearcherMetrics).obj.keySet should contain(PropertyKey.ComponentId)
    writeJs(sampleCanonicalResearcher).obj.keySet should contain(PropertyKey.Provenance)
  }

  "isValidEdgeTypeIdentifier" should "accept every declared edge type" in {
    EdgeType.All.foreach(t => isValidEdgeTypeIdentifier(t) shouldBe true)
  }

  it should "use the same pattern as the storage allowlist" in {
    IdentifierPattern shouldBe "^[A-Za-z_][A-Za-z0-9_]*$"
  }

  it should "reject injection-ish and malformed strings" in {
    val bad = Vector(
      "",
      " ",
      "COAUTHOR ",
      " COAUTHOR",
      "COAUTHOR|AUTHORED",
      "COAUTHOR]-(x) DETACH DELETE x //",
      "COAUTHOR`",
      "`COAUTHOR`",
      "COAUTHOR;",
      "CO-AUTHOR",
      "COAUTHOR*1..99",
      "COAUTHOR\n",
      "COAUTHOR\u0000",
      "1COAUTHOR",
      "CO AUTHOR",
      "COAUTHOR'",
      "COAUTHOR\"",
      "${x}",
      "$param",
      "CÖAUTHOR",
      "COAUTHOR{a:1}",
      "COAUTHOR:AUTHORED"
    )
    bad.foreach { s =>
      withClue(s"input '${s.replace("\u0000", "\\0")}': ") {
        isValidEdgeTypeIdentifier(s) shouldBe false
        isDeclaredEdgeType(s) shouldBe false
      }
    }
  }

  it should "reject null without throwing" in {
    isValidEdgeTypeIdentifier(null) shouldBe false
  }

  "isDeclaredEdgeType" should "accept exactly the declared edge types" in {
    EdgeType.All.foreach(t => isDeclaredEdgeType(t) shouldBe true)
  }

  it should "reject well-formed but undeclared or differently-cased types" in {
    Vector("coauthor", "Coauthor", "CO_AUTHOR", "COAUTHORED", "authored", "KNOWS", "Researcher")
      .foreach(t => isDeclaredEdgeType(t) shouldBe false)
  }

  "isDeclaredVertexLabel" should "accept declared labels and reject others" in {
    VertexLabel.All.foreach(l => isDeclaredVertexLabel(l) shouldBe true)
    Vector("researcher", "RESEARCHER", "Author", "COAUTHOR", "", "Work ")
      .foreach(l => isDeclaredVertexLabel(l) shouldBe false)
  }

  "requireDeclaredEdgeType" should "return a declared type unchanged" in {
    requireDeclaredEdgeType("COAUTHOR") shouldBe "COAUTHOR"
    requireDeclaredEdgeType(EdgeType.Authored) shouldBe EdgeType.Authored
  }

  it should "throw IllegalArgumentException naming the declared types for anything else" in {
    val ex = intercept[IllegalArgumentException](requireDeclaredEdgeType("KNOWS"))
    ex.getMessage should include("KNOWS")
    ex.getMessage should include("COAUTHOR")
    ex.getMessage should include("AUTHORED")
    an[IllegalArgumentException] should be thrownBy requireDeclaredEdgeType("COAUTHOR]-()")
  }

  "GraphEdge.hasDeclaredEdgeType" should "reflect GraphSchema without changing serialization" in {
    sampleGraphEdge.hasDeclaredEdgeType shouldBe true
    sampleGraphEdge.copy(edgeType = "KNOWS").hasDeclaredEdgeType shouldBe false
    writeJs(sampleGraphEdge).obj.keySet.toSet shouldBe Set(
      "edgeId",
      "sourceNodeId",
      "targetNodeId",
      "edgeType",
      "properties",
      "provenance"
    )
  }

  "GraphNode.hasDeclaredVertexLabel" should "reflect GraphSchema without changing serialization" in {
    sampleGraphNode.hasDeclaredVertexLabel shouldBe true
    sampleGraphNode.copy(nodeType = "researcher").hasDeclaredVertexLabel shouldBe false
    writeJs(sampleGraphNode).obj.keySet.toSet shouldBe Set(
      "nodeId",
      "nodeType",
      "properties",
      "provenance"
    )
  }
}
