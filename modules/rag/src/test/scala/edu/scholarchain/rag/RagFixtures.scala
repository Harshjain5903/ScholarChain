package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.{HttpCall, HttpReply, HttpTransport}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** Offline fakes for model adapters and fixture data. Nothing here touches the network. */
object RagFixtures:

  val prov: Provenance = Provenance("fixture", "rec-1", "run-1", "v1", "2026-01-01T00:00:00Z")

  val noSleep: Long => Unit = _ => ()

  // ---- Bedrock ----

  def converseText(text: String): ConverseResponse =
    ConverseResponse
      .builder()
      .output(
        ConverseOutput.fromMessage(
          Message
            .builder()
            .role(ConversationRole.ASSISTANT)
            .content(ContentBlock.fromText(text))
            .build()
        )
      )
      .stopReason(StopReason.END_TURN)
      .build()

  def titanBody(dims: Int): InvokeModelResponse =
    val body = ujson.Obj(
      "embedding"           -> ujson.Arr.from(Vector.fill(dims)(ujson.Num(0.01))),
      "inputTextTokenCount" -> 3
    )
    InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(ujson.write(body))).build()

  def throttle(): ThrottlingException =
    ThrottlingException.builder().message("Too many requests").statusCode(429).build()

  /** Fake Bedrock Runtime client driven by functions; counts and records calls. */
  class FakeBedrock(
    onConverse: ConverseRequest => ConverseResponse = _ => converseText("{}"),
    onInvoke: InvokeModelRequest => InvokeModelResponse = _ => titanBody(1024)
  ) extends BedrockRuntimeClient:
    val converseCalls                  = AtomicInteger(0)
    val invokeCalls                    = AtomicInteger(0)
    val converseRequests               = ArrayBuffer.empty[ConverseRequest]
    val invokeRequests                 = ArrayBuffer.empty[InvokeModelRequest]
    override def serviceName(): String = "bedrock-runtime"
    override def close(): Unit         = ()

    override def converse(r: ConverseRequest): ConverseResponse =
      converseCalls.incrementAndGet()
      synchronized(converseRequests += r)
      onConverse(r)

    override def invokeModel(r: InvokeModelRequest): InvokeModelResponse =
      invokeCalls.incrementAndGet()
      synchronized(invokeRequests += r)
      onInvoke(r)

  // ---- HTTP ----

  class FakeTransport(handler: HttpCall => HttpReply) extends HttpTransport:
    val calls = ArrayBuffer.empty[HttpCall]

    def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
      synchronized(calls += call)
      handler(call)

  def json(status: Int, body: ujson.Value): HttpReply =
    HttpReply(status, Map("content-type" -> "application/json"), ujson.write(body))

  // ---- config ----

  def appConfig(env: Map[String, String] = Map.empty): AppConfig =
    AppConfig.load(TestEmbedders.referenceConfig(env))

  def limiter(n: Int = 4): ConcurrencyLimiter = ConcurrencyLimiter(n, Duration.ofSeconds(5))

  def retry(n: Int): BoundedRetry = BoundedRetry(n, sleep = noSleep)

  // ---- domain fixtures ----

  val adaId   = "researcher:orcid:0000-0001-0000-0001"
  val graceId = "researcher:orcid:0000-0001-0000-0002"
  val alanId  = "researcher:orcid:0000-0001-0000-0003"

  def researcher(id: String, name: String): CanonicalResearcher =
    CanonicalResearcher(id, name, None, None, None, Vector.empty, prov)

  def metrics(id: String, pubs: Int, coauthors: Int): ResearcherMetrics =
    ResearcherMetrics(
      researcherId = id,
      publicationCount = pubs,
      distinctCoauthorCount = coauthors,
      totalCoauthorOccurrences = coauthors * 2,
      averageCoauthorsPerPaper = 2.5,
      maximumCoauthorsOnOnePaper = 6,
      weightedDegree = 14.0,
      componentId = Some("comp-1"),
      communityId = Some("comm-1"),
      bridgeScore = 0.25,
      coreness = 3,
      windowStartYear = 2015,
      windowEndYear = 2024,
      provenance = prov
    )

  def edge(a: String, b: String, joint: Int): CoauthorEdge =
    CoauthorEdge(a, b, joint, 2016, 2022, Vector("work:dblp:x"), prov)

  class MapLookup(byName: Map[String, Vector[CanonicalResearcher]]) extends ResearcherLookup:
    def findByName(name: String): Vector[CanonicalResearcher] = byName.getOrElse(name, Vector.empty)

    def findById(id: String): Option[CanonicalResearcher] =
      byName.values.flatten.find(_.researcherId == id)

  class MapMetrics(m: Map[String, ResearcherMetrics]) extends MetricsStore:
    def getMetrics(id: String, s: Int, e: Int): Option[ResearcherMetrics] = m.get(id)

  class MapGraph(
    coauthors: Map[String, Vector[CoauthorEdge]] = Map.empty,
    paths: Map[(String, String), Vector[String]] = Map.empty
  ) extends GraphStore:
    var pathDepths: Vector[Int] = Vector.empty

    def getDirectCoauthors(id: String, limit: Int): Vector[CoauthorEdge] =
      coauthors.getOrElse(id, Vector.empty).take(limit)

    def shortestPath(from: String, to: String, maxDepth: Int): Option[Vector[String]] =
      pathDepths = pathDepths :+ maxDepth
      paths.get((from, to))

    def getNeighbors(id: String, t: Vector[String], d: Int, l: Int): Vector[GraphNode] =
      Vector.empty

    def getComponentMembers(c: String, limit: Int): Vector[String] = Vector.empty

  object EmptyVectors extends VectorStore:

    def query(
      i: String,
      v: Vector[Float],
      k: Int,
      f: Option[ujson.Value]
    ): Vector[VectorSearchResult] =
      Vector.empty

  /** One hit per index whose text is `text`. */
  class OneHitVectors(entityId: String) extends VectorStore:

    def query(
      i: String,
      v: Vector[Float],
      k: Int,
      f: Option[ujson.Value]
    ): Vector[VectorSearchResult] =
      if i == "paper-abstract-v1" then
        Vector(VectorSearchResult(s"key-$i", 0.8, "paper", entityId, s"s3://bucket/$i.txt"))
      else Vector.empty

  class ConstText(text: String) extends TextStore:
    def getText(uri: String): Option[String] = Some(text)

  class MemoryAudit extends AuditStore:
    var records: Vector[AuditRecord]       = Vector.empty
    def persistAudit(r: AuditRecord): Unit = records = records :+ r

  /** LLM fake: returns scripted replies in order and records every request. */
  class ScriptedLlm(replies: String*) extends LlmClient:
    private val it                        = replies.iterator
    val requests: ArrayBuffer[LlmRequest] = ArrayBuffer.empty

    def generate(prompt: String, maxTokens: Int): String = complete(
      LlmRequest("", prompt, maxTokens)
    )

    override def complete(r: LlmRequest): String =
      requests += r
      if it.hasNext then it.next() else throw new AssertionError("unexpected extra LLM call")

  def claimsJson(claims: (String, Seq[String])*): String =
    ujson.write(
      ujson.Obj(
        "claims" -> ujson.Arr.from(claims.zipWithIndex.map { case ((st, ids), i) =>
          ujson.Obj(
            "claimId"     -> s"c$i",
            "statement"   -> st,
            "confidence"  -> 0.8,
            "evidenceIds" -> ujson.Arr.from(ids.map(ujson.Str(_)))
          )
        })
      )
    )

  def bundle(items: EvidenceItem*): EvidenceBundle =
    EvidenceBundle("b1", "q1", items.toVector, 1.0, prov)

  def item(
    id: String,
    content: String,
    tpe: EvidenceType = EvidenceType.MetricRecord,
    source: String = adaId
  ): EvidenceItem =
    EvidenceItem(id, tpe, source, content, 1.0, prov)

  def plan(question: String = "How many publications?"): QueryPlan =
    QueryPlan(
      "q1",
      question,
      Vector(QueryOperation.ResearcherRanking),
      Map("yearFrom" -> "2015"),
      prov
    )
