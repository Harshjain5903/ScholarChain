package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.rag.RagFixtures.*
import edu.scholarchain.storage.HttpReply
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.bedrockruntime.model.*

import java.time.Duration
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class ModelAdaptersSpec extends AnyFlatSpec with Matchers:

  private val timeout = Duration.ofSeconds(5)

  private def bedrockLlm(fake: FakeBedrock, retries: Int = 3, maxOut: Int = 1024) =
    BedrockConverseLlmClient(
      fake,
      "us.anthropic.claude-haiku-5-5",
      maxOut,
      limiter(),
      retry(retries),
      timeout
    )

  // ---- Bedrock Converse ----

  "BedrockConverseLlmClient" should "send model ID, system block, user message and bounded maxTokens" in {
    val fake = FakeBedrock(onConverse = _ => converseText("hello"))
    val llm  = bedrockLlm(fake, maxOut = 512)
    llm.complete(LlmRequest("SYSTEM RULES", "user text", 4096)) shouldBe "hello"
    val req = fake.converseRequests.head
    req.modelId() shouldBe "us.anthropic.claude-haiku-5-5"
    req.system().asScala.map(_.text()) shouldBe Seq("SYSTEM RULES")
    req.messages().asScala.head.content().asScala.head.text() shouldBe "user text"
    req.inferenceConfig().maxTokens().intValue() shouldBe 512
    req.overrideConfiguration().get().apiCallTimeout().get() shouldBe timeout
  }

  it should "retry throttling and succeed within the retry bound" in {
    val n = AtomicInteger(0)
    val fake = FakeBedrock(onConverse =
      _ => if n.incrementAndGet() <= 2 then throw throttle() else converseText("ok")
    )
    bedrockLlm(fake, retries = 3).generate("p", 10) shouldBe "ok"
    fake.converseCalls.get() shouldBe 3
  }

  it should "stop after the configured number of throttling retries" in {
    val fake = FakeBedrock(onConverse = _ => throw throttle())
    val ex   = intercept[ModelCallException](bedrockLlm(fake, retries = 2).generate("p", 10))
    fake.converseCalls.get() shouldBe 3 // 1 + 2 retries
    ex.retryable shouldBe true
    ex.getMessage should include("ThrottlingException")
  }

  it should "cap retries at ModelCallBounds.MaxRetries whatever the configuration" in {
    val fake = FakeBedrock(onConverse = _ => throw throttle())
    intercept[ModelCallException](bedrockLlm(fake, retries = 50).generate("p", 10))
    fake.converseCalls.get() shouldBe 1 + ModelCallBounds.MaxRetries
  }

  it should "not retry a validation error" in {
    val fake =
      FakeBedrock(onConverse = _ => throw ValidationException.builder().message("bad").build())
    val ex = intercept[ModelCallException](bedrockLlm(fake).generate("p", 10))
    fake.converseCalls.get() shouldBe 1
    ex.retryable shouldBe false
  }

  "ConcurrencyLimiter" should "never allow more in-flight calls than its bound" in {
    val inFlight = AtomicInteger(0)
    val maxSeen  = AtomicInteger(0)
    val fake = FakeBedrock(onConverse = _ =>
      val now = inFlight.incrementAndGet()
      maxSeen.accumulateAndGet(now, math.max)
      Thread.sleep(20)
      inFlight.decrementAndGet()
      converseText("ok")
    )
    val llm  = BedrockConverseLlmClient(fake, "m", 100, limiter(2), retry(0), timeout)
    val pool = Executors.newFixedThreadPool(8)
    val done = CountDownLatch(8)
    (1 to 8).foreach(_ =>
      pool.execute { () =>
        llm.generate("p", 10); done.countDown()
      }
    )
    done.await(10, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()
    maxSeen.get() should be <= 2
    fake.converseCalls.get() shouldBe 8
  }

  // ---- Titan ----

  private def titan(fake: FakeBedrock, dims: Int = 1024) =
    TitanQueryEmbedder(fake, TestEmbedders.TitanV2, dims, limiter(), retry(1), timeout)

  "TitanQueryEmbedder" should "request 1024 normalized dimensions and return the vector" in {
    val fake = FakeBedrock()
    val emb  = titan(fake).embed("graph learning", TestEmbedders.TitanV2)
    emb.vector should have size 1024
    emb.modelId shouldBe TestEmbedders.TitanV2
    val body = ujson.read(fake.invokeRequests.head.body().asUtf8String())
    body("dimensions").num shouldBe 1024
    body("normalize").bool shouldBe true
    body("inputText").str shouldBe "graph learning"
    fake.invokeRequests.head.modelId() shouldBe "amazon.titan-embed-text-v2:0"
  }

  it should "reject a returned vector whose dimension differs from the configuration" in {
    val fake = FakeBedrock(onInvoke = _ => titanBody(512))
    val ex   = intercept[EmbeddingDimensionException](titan(fake).embed("x", TestEmbedders.TitanV2))
    ex.expected shouldBe 1024
    ex.actual shouldBe 512
  }

  it should "make SemanticContextAgent abstain without querying any index on a dimension mismatch" in {
    val vectors = TestEmbedders.RecordingVectorStore()
    val agent = SemanticContextAgent(
      vectors,
      TestEmbedders.ConstTextStore(),
      titan(FakeBedrock(onInvoke = _ => titanBody(768))),
      TestEmbedders.defaultEmbeddingConfig
    )
    val bundle = agent.retrieve(Vector.empty, Vector.empty, "q")
    bundle.failure.map(_.reason) shouldBe Some(SemanticFailureReason.EmbeddingDimensionMismatch)
    vectors.queries shouldBe empty
  }

  it should "retry Titan throttling within bounds" in {
    val fake = FakeBedrock(onInvoke = _ => throw throttle())
    intercept[ModelCallException](titan(fake).embed("x", TestEmbedders.TitanV2))
    fake.invokeCalls.get() shouldBe 2 // 1 + 1 retry
  }

  // ---- Anthropic / Voyage over HTTP ----

  private val secretKey = "sk-ant-test-0123456789-SECRET"

  "AnthropicMessagesLlmClient" should "send the key only in x-api-key and parse text blocks" in {
    val t = FakeTransport(_ =>
      json(200, ujson.Obj("content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "answer"))))
    )
    val llm = AnthropicMessagesLlmClient(
      t,
      () => secretKey,
      "claude-haiku-5-5",
      256,
      limiter(),
      retry(2),
      timeout
    )
    llm.complete(LlmRequest("sys", "usr", 1000)) shouldBe "answer"
    val call = t.calls.head
    call.header("x-api-key") shouldBe Some(secretKey)
    call.header("anthropic-version") shouldBe Some("2023-06-01")
    val body = ujson.read(call.bodyString)
    body("model").str shouldBe "claude-haiku-5-5"
    body("max_tokens").num shouldBe 256
    body("system").str shouldBe "sys"
    call.bodyString should not include secretKey
    llm.toString should not include secretKey
  }

  it should "retry 429 within bounds and never put the key in the error" in {
    val t = FakeTransport(_ =>
      json(
        429,
        ujson.Obj("error" -> ujson.Obj("type" -> "rate_limit_error", "message" -> secretKey))
      )
    )
    val llm = AnthropicMessagesLlmClient(
      t,
      () => secretKey,
      "claude-haiku-5-5",
      256,
      limiter(),
      retry(2),
      timeout
    )
    val ex = intercept[ModelCallException](llm.generate("p", 10))
    t.calls should have size 3
    ex.getMessage should include("status=429")
    ex.getMessage should not include secretKey
  }

  it should "not retry an authentication failure" in {
    val t = FakeTransport(_ =>
      json(401, ujson.Obj("error" -> ujson.Obj("type" -> "authentication_error")))
    )
    val llm = AnthropicMessagesLlmClient(t, () => secretKey, "m", 256, limiter(), retry(3), timeout)
    intercept[ModelCallException](llm.generate("p", 10)).retryable shouldBe false
    t.calls should have size 1
  }

  "VoyageQueryEmbedder" should "send a bearer key, query input type and output dimension" in {
    val t = FakeTransport(_ =>
      json(
        200,
        ujson.Obj(
          "data" -> ujson.Arr(
            ujson.Obj("embedding" -> ujson.Arr.from(Vector.fill(1024)(ujson.Num(0.1))))
          )
        )
      )
    )
    val v =
      VoyageQueryEmbedder(t, () => secretKey, "voyage-3.5", 1024, limiter(), retry(1), timeout)
    val emb = v.embed("q", "voyage-3.5")
    emb.vector should have size 1024
    t.calls.head.header("authorization") shouldBe Some(s"Bearer $secretKey")
    val body = ujson.read(t.calls.head.bodyString)
    body("input_type").str shouldBe "query"
    body("output_dimension").num shouldBe 1024
  }

  it should "reject a dimension mismatch" in {
    val t = FakeTransport(_ =>
      json(200, ujson.Obj("data" -> ujson.Arr(ujson.Obj("embedding" -> ujson.Arr(0.1, 0.2)))))
    )
    val v =
      VoyageQueryEmbedder(t, () => secretKey, "voyage-3.5", 1024, limiter(), retry(1), timeout)
    intercept[EmbeddingDimensionException](v.embed("q", "voyage-3.5")).actual shouldBe 2
  }

  // ---- provider selection ----

  private def clients(fake: FakeBedrock, transport: FakeTransport) =
    ModelClients(bedrock = _ => fake, http = () => transport, sleep = noSleep)

  private class CountingResolver(value: String) extends SecretResolver:
    val calls                           = AtomicInteger(0)
    def resolve(ref: SecretRef): String = { calls.incrementAndGet(); value }

  "ModelAdapters" should "select Bedrock Converse and Titan by default" in {
    val cfg = appConfig()
    val r   = CountingResolver("unused")
    val t   = FakeTransport(_ => HttpReply(500, Map.empty, ""))
    ModelAdapters.llm(cfg.llm, cfg.operations, clients(FakeBedrock(), t), r) shouldBe a[
      BedrockConverseLlmClient
    ]
    ModelAdapters.embedder(cfg.embedding, cfg.operations, clients(FakeBedrock(), t), r) shouldBe a[
      TitanQueryEmbedder
    ]
    cfg.llm.modelId shouldBe "us.anthropic.claude-haiku-5-5"
    r.calls.get() shouldBe 0
  }

  it should "select Anthropic and Voyage by provider and resolve keys only on first call" in {
    val cfg = appConfig(
      Map(
        "SCHOLARCHAIN_LLM_PROVIDER"              -> "anthropic",
        "SCHOLARCHAIN_LLM_MODEL_ID"              -> "claude-haiku-5-5",
        "SCHOLARCHAIN_ANTHROPIC_API_KEY_ENV_VAR" -> "TEST_ANTHROPIC_KEY",
        "SCHOLARCHAIN_EMBEDDING_PROVIDER"        -> "voyage",
        "SCHOLARCHAIN_EMBEDDING_MODEL_ID"        -> "voyage-3.5",
        "SCHOLARCHAIN_VOYAGE_API_KEY_ENV_VAR"    -> "TEST_VOYAGE_KEY"
      )
    )
    val r = CountingResolver(secretKey)
    val t = FakeTransport(_ =>
      json(200, ujson.Obj("content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "ok"))))
    )
    val llm = ModelAdapters.llm(cfg.llm, cfg.operations, clients(FakeBedrock(), t), r)
    val emb = ModelAdapters.embedder(cfg.embedding, cfg.operations, clients(FakeBedrock(), t), r)
    llm shouldBe a[AnthropicMessagesLlmClient]
    emb shouldBe a[VoyageQueryEmbedder]
    r.calls.get() shouldBe 0
    llm.generate("p", 10) shouldBe "ok"
    llm.generate("p", 10) shouldBe "ok"
    r.calls.get() shouldBe 1
  }

  // ---- secrets ----

  "EnvSecretResolver" should "read an env-var reference and fail without echoing values" in {
    val r = EnvSecretResolver(Map("MY_KEY" -> secretKey).get)
    r.resolve(SecretRef.envVar("MY_KEY")) shouldBe secretKey
    val ex = intercept[SecretResolutionException](r.resolve(SecretRef.envVar("OTHER_KEY")))
    ex.getMessage should include("OTHER_KEY")
  }

  "SecretsManagerSecretResolver" should "call GetSecretValue in the ARN region and extract api_key" in {
    val arn = "arn:aws:secretsmanager:us-east-2:123456789012:secret:scholarchain/anthropic-AbCdEf"
    val t = FakeTransport(_ =>
      json(200, ujson.Obj("SecretString" -> ujson.write(ujson.Obj("api_key" -> secretKey))))
    )
    val creds =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secretexample"))
    val r = SecretsManagerSecretResolver(t, creds)
    r.resolve(SecretRef.arn(arn)) shouldBe secretKey
    val call = t.calls.head
    call.uri.getHost shouldBe "secretsmanager.us-east-2.amazonaws.com"
    call.header("X-Amz-Target") shouldBe Some("secretsmanager.GetSecretValue")
    call.header("Authorization").get should include("/us-east-2/secretsmanager/aws4_request")
  }

  it should "report a failed lookup by reference only" in {
    val arn = "arn:aws:secretsmanager:us-east-2:123456789012:secret:x-AbCdEf"
    val t   = FakeTransport(_ => json(400, ujson.Obj("message" -> secretKey)))
    val creds =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secretexample"))
    val ex = intercept[SecretResolutionException](
      SecretsManagerSecretResolver(t, creds).resolve(SecretRef.arn(arn))
    )
    ex.getMessage should include("status=400")
    ex.getMessage should not include secretKey
  }
