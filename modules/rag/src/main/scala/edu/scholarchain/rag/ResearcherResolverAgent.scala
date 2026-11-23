package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class ResolvedResearcher(
  researcher: CanonicalResearcher,
  confidence: Double,
  alternates: Vector[CanonicalResearcher]
) derives ReadWriter

/** Resolves researcher names to canonical identities. Never silently picks one when ambiguous. */
class ResearcherResolverAgent(lookup: ResearcherLookup):

  def resolve(names: Vector[String]): Vector[ResolvedResearcher] =
    names.flatMap(resolveSingle)

  private def resolveSingle(name: String): Option[ResolvedResearcher] =
    val candidates = lookup.findByName(name)
    candidates.size match
      case 0 =>
        val placeholder = CanonicalResearcher(
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
        )
        Some(
          ResolvedResearcher(
            researcher = placeholder,
            confidence = 0.0,
            alternates = Vector.empty
          )
        )
      case 1 =>
        Some(
          ResolvedResearcher(
            researcher = candidates.head,
            confidence = 1.0,
            alternates = Vector.empty
          )
        )
      case _ =>
        Some(
          ResolvedResearcher(
            researcher = candidates.head,
            confidence = 0.5,
            alternates = candidates.tail
          )
        )
