package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class ResolvedResearcher(
  researcher: CanonicalResearcher,
  confidence: Double,
  alternates: Vector[CanonicalResearcher]
) derives ReadWriter:

  /** Exactly one canonical identity matched. */
  def isUnambiguous: Boolean =
    confidence >= 1.0 && alternates.isEmpty && researcher.researcherId.nonEmpty

/** Resolves researcher names to canonical identities. Never silently picks one when ambiguous. */
class ResearcherResolverAgent(lookup: ResearcherLookup):

  def resolve(names: Vector[String]): Vector[ResolvedResearcher] =
    names.map(resolveSingle)

  /**
   * Resolve explicit researcher IDs (for example from a `researcherId` constraint). A known ID is
   * unambiguous (confidence 1.0); an unknown ID resolves with confidence 0.0, like an unknown name.
   */
  def resolveIds(ids: Vector[String]): Vector[ResolvedResearcher] =
    ids.map { id =>
      lookup.findById(id) match
        case Some(r) => ResolvedResearcher(r, 1.0, Vector.empty)
        case None    => unresolved(id)
    }

  private def unresolved(name: String): ResolvedResearcher =
    ResolvedResearcher(
      researcher = CanonicalResearcher(
        researcherId = "",
        displayName = name,
        orcid = None,
        openAlexId = None,
        semanticScholarId = None,
        alternateNames = Vector.empty,
        provenance = Provenance(
          sourceDataset = "resolver",
          sourceRecordId = name,
          pipelineRunId = "unresolved",
          schemaVersion = "v1",
          timestamp = java.time.Instant.now().toString
        )
      ),
      confidence = 0.0,
      alternates = Vector.empty
    )

  private def resolveSingle(name: String): ResolvedResearcher =
    val candidates = lookup.findByName(name)
    candidates.size match
      case 0 => unresolved(name)
      case 1 => ResolvedResearcher(candidates.head, 1.0, Vector.empty)
      case _ => ResolvedResearcher(candidates.head, 0.5, candidates.tail)
