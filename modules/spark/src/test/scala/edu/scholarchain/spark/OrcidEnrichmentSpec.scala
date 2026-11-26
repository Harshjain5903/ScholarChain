package edu.scholarchain.spark

import edu.scholarchain.core.*
import edu.scholarchain.spark.TestFixtures.mkProv

/** ORCID (third enrichment dataset) linked to canonical researchers by exact ORCID iD. */
class OrcidEnrichmentSpec extends SparkTestBase:

  private val idA = "0000-0002-1825-0097"
  private val idB = "0000-0001-5109-3700"

  private def mention(id: String, name: String, orcid: Option[String], aff: Option[String] = None) =
    AuthorMention(id, name, name.toLowerCase, 0, orcid, None, aff, id, mkProv(id))

  private def orcidLines: Seq[String] =
    val prov = mkProv(s"orcid:$idA").copy(sourceDataset = "orcid")
    Seq(
      upickle.default.write(
        mention(s"mention:orcid:orcid:$idA:0", "Ada Lovelace", Some(idA), Some("Univ X"))
          .copy(provenance = prov)
      ),
      upickle.default.write(
        CanonicalResearcher(
          "researcher:x",
          "Ada Lovelace",
          Some(idA),
          None,
          None,
          Vector("A. Lovelace", "Augusta Ada King"),
          prov
        )
      ),
      "not json",
      ""
    )

  test("normalizeOrcid accepts bare iDs and orcid.org URLs, rejects garbage") {
    OrcidEnrichment.normalizeOrcid(s"https://orcid.org/$idA") shouldBe Some(idA)
    OrcidEnrichment.normalizeOrcid("0000-0002-1694-233x") shouldBe Some("0000-0002-1694-233X")
    OrcidEnrichment.normalizeOrcid("no id here") shouldBe None
  }

  test("profiles merge the mention (affiliation) and researcher (name variants) lines") {
    val p = OrcidEnrichment.profiles(sc.parallelize(orcidLines)).collect().toMap
    p.keySet shouldBe Set(idA)
    p(idA).name shouldBe "Ada Lovelace"
    p(idA).affiliation shouldBe Some("Univ X")
    p(idA).alternateNames shouldBe Vector("A. Lovelace", "Augusta Ada King")
  }

  test("a canonical researcher is linked through any merged mention carrying the iD") {
    val teams = sc.parallelize(
      Seq(
        PublicationTeam(
          "w1",
          2020,
          Vector(
            mention("mention:dblp:w1:0", "Ada Lovelace", Some(s"https://orcid.org/$idA")),
            mention("mention:dblp:w1:1", "Bob", None)
          ),
          2,
          mkProv("w1")
        ),
        PublicationTeam(
          "w2",
          2021,
          Vector(mention("mention:dblp:w2:0", "C. Babbage", Some(idB))),
          1,
          mkProv("w2")
        )
      )
    )
    // mention:dblp:w1:0 was merged into canonical researcher R1 by entity resolution.
    val mapping = sc.parallelize(Seq("mention:dblp:w1:0" -> "R1"))
    val rOrcids = OrcidEnrichment.researcherOrcids(teams, mapping)
    rOrcids.collect().toMap shouldBe Map(
      "R1"                -> (idA, false),
      "mention:dblp:w2:0" -> (idB, false)
    )
    val links = OrcidEnrichment
      .link(rOrcids, OrcidEnrichment.profiles(sc.parallelize(orcidLines)))
      .collect()
    // idB has no fetched ORCID record: only R1 is linked.
    links.map(l => (l.researcherId, l.orcid, l.affiliation)).toSet shouldBe Set(
      ("R1", idA, Some("Univ X"))
    )
  }

  test("disagreeing iDs on one researcher pick the most frequent and are flagged") {
    val teams = sc.parallelize(
      Seq("w1", "w2", "w3").zipWithIndex.map { case (w, i) =>
        val o = if i < 2 then idA else idB
        PublicationTeam(w, 2020, Vector(mention(s"m:$w", "X", Some(o))), 1, mkProv(w))
      }
    )
    val mapping = sc.parallelize(Seq("m:w1" -> "R", "m:w2" -> "R", "m:w3" -> "R"))
    OrcidEnrichment.researcherOrcids(teams, mapping).collect().toMap shouldBe Map(
      "R" -> (idA, true)
    )
  }
