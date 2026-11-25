package edu.scholarchain.hadoop.counters

/**
 * Counter group and name constants for all ScholarChain MapReduce jobs. Every job increment
 * counters from its own group plus the shared group.
 */
object HadoopCounters:

  // -- Shared counter group used by all jobs --
  val SharedGroup = "ScholarChain"

  val InputRecords          = "input_records"
  val Accepted              = "accepted"
  val Rejected              = "rejected"
  val MissingRequiredFields = "missing_required_fields"

  /** Per-reason reject counts; counter name is the reject reason code (e.g. MALFORMED_XML). */
  val RejectReasonGroup = "RejectReasons"

  // -- DBLP extraction --
  val DblpGroup          = "DblpExtraction"
  val ArticleCount       = "article_count"
  val InproceedingsCount = "inproceedings_count"
  val ProceedingsCount   = "proceedings_count"
  val BookCount          = "book_count"
  val IncollectionCount  = "incollection_count"
  val PhdthesisCount     = "phdthesis_count"
  val MastersthesisCount = "mastersthesis_count"

  // -- Identifier extraction --
  val IdentifierGroup  = "IdentifierExtraction"
  val TotalIdentifiers = "total_identifiers"
  val Duplicates       = "duplicates"
  val Conflicts        = "conflicts"
  val MissingKeys      = "missing_keys"

  /** Researcher lines of the ORCID normalizer output: carry no identifier pairs, skipped. */
  val SkippedResearcherRecords = "skipped_researcher_records"

  /**
   * DBLP works (counted once each, a work has one DOI) whose normalized DOI is also carried by a
   * Crossref / OpenAlex / both / either record.
   */
  val DblpWorksCrossrefDoiMatch  = "dblp_works_crossref_doi_match"
  val DblpWorksOpenAlexDoiMatch  = "dblp_works_openalex_doi_match"
  val DblpWorksBothDoiMatch      = "dblp_works_both_doi_match"
  val DblpWorksAnyDoiMatch       = "dblp_works_any_doi_match"
  val CrossrefOpenAlexDoiMatches = "doi_groups_crossref_openalex"

  /**
   * Per identifier type `t` (doi, arxiv, orcid, openalex): `t_keys`, `t_cross_source`,
   * `t_same_source_duplicates`.
   */

  // -- Blocking --
  val BlockingGroup   = "Blocking"
  val TotalBlocks     = "total_blocks"
  val OversizeBlocks  = "oversize_blocks"
  val RecordsInBlocks = "records_in_blocks"

  /** Members cut from oversize blocks (true size minus the kept maximum). */
  val DroppedRecords = "dropped_records"

  /** Size of the largest block before truncation (sum of per-reducer maxima; exact with one). */
  val LargestBlockSize = "largest_block_size"

  /** Histogram of true block sizes; counter names come from `BlockingJob.sizeBucket`. */
  val BlockSizeGroup = "BlockSizes"

  // -- Feature construction --
  val FeatureGroup          = "FeatureConstruction"
  val PairsCompared         = "pairs_compared"
  val FeaturesComputed      = "features_computed"
  val OversizeFeatureBlocks = "oversize_feature_blocks"

  // -- Team extraction --
  val TeamGroup               = "TeamExtraction"
  val TotalTeams              = "total_teams"
  val TotalAuthorPaperRecords = "total_author_paper_records"
  val LargeTeams              = "large_teams"

  // -- Pair expansion --
  val PairGroup             = "PairExpansion"
  val TotalPairsEmitted     = "total_pairs_emitted"
  val PapersProcessed       = "papers_processed"
  val LargeTeamExpansions   = "large_team_expansions"
  val SkippedSingletonTeams = "skipped_singleton_teams"

  /** Author-paper lines co-located with team records in J06 output; skipped, not rejected. */
  val SkippedAuthorPaperRecords = "skipped_author_paper_records"

  // -- Graph export --
  val ExportGroup       = "GraphExport"
  val WorkRecordsOut    = "work_records_out"
  val AuthorMentionsOut = "author_mentions_out"
  val TeamsOut          = "teams_out"
  val PairCandidatesOut = "pair_candidates_out"

  /** J08 records of unknown type written to the export root (flagged by ExportVerifier). */
  val UnknownRecordsOut = "unknown_records_out"
