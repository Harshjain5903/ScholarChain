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
    EdgeType.Coauthor shouldBe "COAUTHORED_WITH"
    EdgeType.Authored shouldBe "AUTHORED"
    EdgeType.All shouldBe Vector("COAUTHORED_WITH", "AUTHORED")
  }

  it should "no longer declare the retired COAUTHOR value (DOM-P12-R08)" in {
    EdgeType.All should not contain "COAUTHOR"
    isDeclaredEdgeType("COAUTHOR") shouldBe false
    an[IllegalArgumentException] should be thrownBy requireDeclaredEdgeType("COAUTHOR")
  }

  "GraphSchema.PropertyKey" should "pin the exact vertex key values" in {
    PropertyKey.NodeId shouldBe "nodeId"
    PropertyKey.ResearcherId shouldBe "researcherId"
    PropertyKey.ComponentId shouldBe "componentId"
    PropertyKey.WorkId shouldBe "workId"
    PropertyKey.VertexKeys shouldBe Vector("nodeId", "researcherId", "componentId", "workId")
  }

  it should "pin the exact COAUTHORED_WITH edge key values" in {
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

  it should "pin the exact descriptive vertex property key values (N41)" in {
    PropertyKey.DisplayName shouldBe "displayName"
    PropertyKey.Title shouldBe "title"
    PropertyKey.Year shouldBe "year"
    PropertyKey.Venue shouldBe "venue"
    PropertyKey.ResearcherDescriptiveKeys shouldBe Vector("displayName")
    PropertyKey.WorkDescriptiveKeys shouldBe Vector("title", "year", "venue")
  }

  it should "list every declared key exactly once in All, grouped vertex then edge" in {
    PropertyKey.All shouldBe Vector(
      "nodeId",
      "researcherId",
      "componentId",
      "workId",
      "displayName",
      "title",
      "year",
      "venue",
      "researcherA",
      "researcherB",
      "jointPublicationCount",
      "firstCollaborationYear",
      "lastCollaborationYear",
      "representativeWorkIds",
      "provenance"
    )
    PropertyKey.All.distinct shouldBe PropertyKey.All
    PropertyKey.All.size shouldBe 15
  }

  it should "keep descriptive keys disjoint from identity and edge keys" in {
    val descriptive = PropertyKey.ResearcherDescriptiveKeys ++ PropertyKey.WorkDescriptiveKeys
    (descriptive.toSet intersect PropertyKey.VertexKeys.toSet) shouldBe empty
    (descriptive.toSet intersect PropertyKey.CoauthorEdgeKeys.toSet) shouldBe empty
  }

  "GraphSchema declared names" should "be unique within each category" in {
    VertexLabel.All.distinct shouldBe VertexLabel.All
    EdgeType.All.distinct shouldBe EdgeType.All
    PropertyKey.VertexKeys.distinct shouldBe PropertyKey.VertexKeys
    PropertyKey.CoauthorEdgeKeys.distinct shouldBe PropertyKey.CoauthorEdgeKeys
    PropertyKey.WorkDescriptiveKeys.distinct shouldBe PropertyKey.WorkDescriptiveKeys
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
    writeJs(sampleCanonicalResearcher).obj.keySet should contain(PropertyKey.DisplayName)
    writeJs(sampleWorkRecord).obj.keySet should contain(PropertyKey.Title)
    writeJs(sampleWorkRecord).obj.keySet should contain(PropertyKey.Year)
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
    Vector(
      "coauthor",
      "Coauthor",
      "CO_AUTHOR",
      "COAUTHORED",
      "COAUTHOR",
      "coauthored_with",
      "authored",
      "KNOWS",
      "Researcher"
    )
      .foreach(t => isDeclaredEdgeType(t) shouldBe false)
  }

  "isDeclaredVertexLabel" should "accept declared labels and reject others" in {
    VertexLabel.All.foreach(l => isDeclaredVertexLabel(l) shouldBe true)
    Vector("researcher", "RESEARCHER", "Author", "COAUTHOR", "", "Work ")
      .foreach(l => isDeclaredVertexLabel(l) shouldBe false)
  }

  "requireDeclaredEdgeType" should "return a declared type unchanged" in {
    requireDeclaredEdgeType("COAUTHORED_WITH") shouldBe "COAUTHORED_WITH"
    requireDeclaredEdgeType(EdgeType.Authored) shouldBe EdgeType.Authored
  }

  it should "throw IllegalArgumentException naming the declared types for anything else" in {
    val ex = intercept[IllegalArgumentException](requireDeclaredEdgeType("KNOWS"))
    ex.getMessage should include("KNOWS")
    ex.getMessage should include("COAUTHORED_WITH")
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
