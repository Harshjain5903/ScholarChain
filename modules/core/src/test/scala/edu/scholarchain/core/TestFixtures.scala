package edu.scholarchain.core

/** Shared test fixtures for core domain tests. */
object TestFixtures:

  val sampleProvenance: Provenance = Provenance(
    sourceDataset = "openalex",
    sourceRecordId = "W123456",
    pipelineRunId = "run:test-001",
    schemaVersion = "1.0.0",
    timestamp = "2024-01-15T10:30:00Z"
  )

  val sampleAuthorMention: AuthorMention = AuthorMention(
    mentionId = "mention:openalex:A100:W123456",
    rawName = "Jane A. Smith",
    normalizedName = "jane a smith",
    position = 0,
    orcid = Some("0000-0001-2345-6789"),
    openAlexId = Some("A100"),
    affiliationRaw = Some("MIT"),
    sourceRecordId = "W123456",
    provenance = sampleProvenance
  )

  val sampleAuthorMention2: AuthorMention = AuthorMention(
    mentionId = "mention:openalex:A200:W123456",
    rawName = "Bob Chen",
    normalizedName = "bob chen",
    position = 1,
    orcid = None,
    openAlexId = Some("A200"),
    affiliationRaw = Some("Stanford University"),
    sourceRecordId = "W123456",
    provenance = sampleProvenance
  )

  val sampleWorkRecord: WorkRecord = WorkRecord(
    workId = "work:openalex:W123456",
    source = SourceType.OpenAlex,
    sourceRecordId = "W123456",
    title = "Deep Learning for Graph Analytics",
    normalizedTitle = "deep learning for graph analytics",
    year = 2023,
    venueId = Some("venue:openalex:V789"),
    doi = Some("10.1234/example.2023"),
    arxivId = None,
    authors = Vector(sampleAuthorMention, sampleAuthorMention2),
    abstractTextRef = None,
    provenance = sampleProvenance
  )

  val sampleCoauthorEdge: CoauthorEdge = CoauthorEdge(
    researcherA = "researcher:orcid:0000-0001-2345-6789",
    researcherB = "researcher:openalex:A200",
    jointPublicationCount = 5,
    firstCollaborationYear = 2019,
    lastCollaborationYear = 2023,
    representativeWorkIds =
      Vector("work:openalex:W123456", "work:openalex:W789012"),
    provenance = sampleProvenance
  )

  val sampleMatchDecision: MatchDecision = MatchDecision(
    sourceId = "mention:openalex:A100:W123456",
    targetId = "mention:dblp:auth-smith:conf/icml/2023",
    features = Map(
      "nameJaroWinkler"    -> 0.95,
      "affiliationOverlap" -> 0.80,
      "yearProximity"      -> 1.0
    ),
    decision = MatchOutcome.Accepted,
    confidence = 0.92,
    reasonCodes = Vector("HIGH_NAME_SIM", "SAME_VENUE"),
    policyVersion = "v1",
    runId = "run:test-001"
  )

  val sampleVenue: Venue = Venue(
    venueId = "venue:openalex:V789",
    name = "International Conference on Machine Learning",
    normalizedName = "international conference on machine learning",
    source = SourceType.OpenAlex,
    provenance = sampleProvenance
  )

  val sampleInstitution: Institution = Institution(
    institutionId = "inst:openalex:I100",
    name = "Massachusetts Institute of Technology",
    normalizedName = "massachusetts institute of technology",
    source = SourceType.OpenAlex,
    provenance = sampleProvenance
  )

  val sampleTopic: Topic = Topic(
    topicId = "topic:openalex:T50",
    name = "Graph Neural Networks",
    normalizedName = "graph neural networks",
    source = SourceType.OpenAlex,
    provenance = sampleProvenance
  )

  val sampleCanonicalResearcher: CanonicalResearcher = CanonicalResearcher(
    researcherId = "researcher:orcid:0000-0001-2345-6789",
    displayName = "Jane A. Smith",
    orcid = Some("0000-0001-2345-6789"),
    openAlexId = Some("A100"),
    semanticScholarId = None,
    alternateNames = Vector("J. Smith", "Jane Smith"),
    provenance = sampleProvenance
  )

  val sampleAuthorshipRecord: AuthorshipRecord = AuthorshipRecord(
    researcherId = "researcher:orcid:0000-0001-2345-6789",
    workId = "work:openalex:W123456",
    authorPosition = 0,
    confidence = 0.95,
    provenance = sampleProvenance
  )

  val sampleGraphNode: GraphNode = GraphNode(
    nodeId = "researcher:orcid:0000-0001-2345-6789",
    nodeType = "Researcher",
    properties = Map("displayName" -> "Jane A. Smith", "hIndex" -> "42"),
    provenance = sampleProvenance
  )

  val sampleGraphEdge: GraphEdge = GraphEdge(
    edgeId = "edge:authored:R1:W1",
    sourceNodeId = "researcher:orcid:0000-0001-2345-6789",
    targetNodeId = "work:openalex:W123456",
    edgeType = "AUTHORED",
    properties = Map("position" -> "0", "confidence" -> "0.95"),
    provenance = sampleProvenance
  )

  val sampleEvidenceItem: EvidenceItem = EvidenceItem(
    evidenceId = "ev:chunk:001",
    evidenceType = EvidenceType.TextChunk,
    sourceEntityId = "work:openalex:W123456",
    content = "Graph neural networks have shown remarkable performance...",
    score = 0.88,
    provenance = sampleProvenance
  )

  val sampleEvidenceBundle: EvidenceBundle = EvidenceBundle(
    bundleId = "bundle:q1:001",
    queryId = "query:001",
    items = Vector(sampleEvidenceItem),
    totalScore = 0.88,
    provenance = sampleProvenance
  )

  val sampleQueryPlan: QueryPlan = QueryPlan(
    queryId = "query:001",
    originalQuery = "Who are the top collaborators in graph neural networks?",
    operations = Vector(
      QueryOperation.TopicFilter,
      QueryOperation.ResearcherRanking
    ),
    filters = Map("topic" -> "graph neural networks", "minYear" -> "2020"),
    provenance = sampleProvenance
  )

  val sampleAnswerClaim: AnswerClaim = AnswerClaim(
    claimId = "claim:q1:001",
    queryId = "query:001",
    statement = "Jane Smith is a leading collaborator in GNN research.",
    confidence = 0.85,
    supportingEvidence = Vector("ev:chunk:001"),
    verificationStatus = VerificationStatus.Verified,
    provenance = sampleProvenance
  )

  val sampleAnswerResponse: AnswerResponse = AnswerResponse(
    responseId = "response:q1:001",
    queryId = "query:001",
    claims = Vector(sampleAnswerClaim),
    evidenceBundle = sampleEvidenceBundle,
    generatedText =
      "Based on the analysis, Jane Smith is a leading collaborator.",
    provenance = sampleProvenance
  )

  val samplePublicationTeam: PublicationTeam = PublicationTeam(
    workId = "work:openalex:W123456",
    year = 2023,
    authorMentions = Vector(sampleAuthorMention, sampleAuthorMention2),
    teamSize = 2,
    provenance = sampleProvenance
  )

  val sampleCoauthorPairCandidate: CoauthorPairCandidate =
    CoauthorPairCandidate(
      mentionA = "mention:openalex:A100:W123456",
      mentionB = "mention:openalex:A200:W123456",
      workId = "work:openalex:W123456",
      year = 2023,
      provenance = sampleProvenance
    )

  val sampleResearcherMetrics: ResearcherMetrics = ResearcherMetrics(
    researcherId = "researcher:orcid:0000-0001-2345-6789",
    publicationCount = 42,
    distinctCoauthorCount = 15,
    totalCoauthorOccurrences = 120,
    averageCoauthorsPerPaper = 2.86,
    maximumCoauthorsOnOnePaper = 8,
    weightedDegree = 35.5,
    componentId = Some("comp:001"),
    communityId = Some("comm:003"),
    bridgeScore = 0.72,
    coreness = 3,
    windowStartYear = 2019,
    windowEndYear = 2023,
    provenance = sampleProvenance
  )
