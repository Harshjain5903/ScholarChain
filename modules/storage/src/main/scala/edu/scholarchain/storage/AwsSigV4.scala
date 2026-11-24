package edu.scholarchain.storage

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, AwsSessionCredentials}
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpFullRequest, SdkHttpMethod}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.http.auth.spi.signer.{HttpSigner, SignRequest}
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Clock, Duration}
import scala.jdk.CollectionConverters.*
import scala.util.Try

// ---------------------------------------------------------------------------
// Minimal HTTP transport abstraction (lets tests use a fake, no network)
// ---------------------------------------------------------------------------

/** An outbound HTTP request. Header order is preserved; names are case-insensitive. */
final case class HttpCall(
  method: String,
  uri: URI,
  headers: Vector[(String, String)],
  body: Array[Byte]
):

  /** First value of a header, matched case-insensitively. */
  def header(name: String): Option[String] =
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  def bodyString: String = String(body, "UTF-8")

/** An HTTP response. Header names are lower-cased. */
final case class HttpReply(status: Int, headers: Map[String, String], body: String):
  def isSuccess: Boolean = status >= 200 && status < 300

/** Sends an already-signed [[HttpCall]]. */
trait HttpTransport:
  def execute(call: HttpCall, timeout: Option[Duration]): HttpReply

/** [[HttpTransport]] backed by `java.net.http.HttpClient`. */
final class JavaHttpTransport(client: HttpClient) extends HttpTransport:

  // java.net.http sets these itself (Host is derived from the URI, which is
  // what was signed) and throws IllegalArgumentException if they are supplied.
  private val restricted = Set("host", "content-length", "connection", "expect", "upgrade")

  def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
    val builder = HttpRequest
      .newBuilder()
      .uri(call.uri)
      .method(call.method, HttpRequest.BodyPublishers.ofByteArray(call.body))
    timeout.foreach(builder.timeout)
    call.headers.foreach { case (k, v) =>
      if !restricted.contains(k.toLowerCase) then builder.header(k, v)
    }
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    val headers = response
      .headers()
      .map()
      .asScala
      .map { case (k, vs) => k.toLowerCase -> vs.asScala.headOption.getOrElse("") }
      .toMap
    HttpReply(response.statusCode(), headers, response.body())

object JavaHttpTransport:

  /** HTTP/1.1 client: both Neptune and S3 Vectors accept it and it avoids h2c upgrade headers. */
  def create(connectTimeout: Duration): JavaHttpTransport =
    JavaHttpTransport(
      HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(connectTimeout)
        .build()
    )

// ---------------------------------------------------------------------------
// SigV4 signing
// ---------------------------------------------------------------------------

/**
 * Signs [[HttpCall]]s with AWS Signature Version 4 using the SDK's `AwsV4HttpSigner`.
 *
 * Credentials come from the injected provider and are resolved per request (so rotated role
 * credentials are picked up). They are never logged; `toString` shows only service and region, and
 * [[redact]] removes credential material from any text before it is logged or thrown.
 */
final class SigV4Signer(
  credentialsProvider: AwsCredentialsProvider,
  val region: String,
  val signingName: String,
  clock: Clock = Clock.systemUTC()
):
  require(SigV4Signer.isValidRegion(region), s"invalid AWS region '$region'")
  require(signingName.trim.nonEmpty, "signing name must be non-empty")

  private val signer = AwsV4HttpSigner.create()

  // The SDK signer logs the canonical request at DEBUG; with session (role) credentials that
  // includes x-amz-security-token. Logging config is owned outside storage, so warn loudly.
  // Checked on first use, not construction: during SLF4J start-up a SubstituteLogger reports
  // DEBUG as enabled and would give a false warning.
  private lazy val sdkDebugCheck: Unit =
    val sdkLogger = org.slf4j.LoggerFactory.getLogger(SigV4Signer.SdkSignerLoggerName)
    if !sdkLogger.isInstanceOf[org.slf4j.helpers.SubstituteLogger] && sdkLogger.isDebugEnabled then
      org.slf4j.LoggerFactory
        .getLogger(classOf[SigV4Signer])
        .warn(SigV4Signer.SdkDebugWarning)

  /** Return a copy of `call` carrying the SigV4 headers (Authorization, X-Amz-Date, Host, ...). */
  def sign(call: HttpCall): HttpCall =
    sdkDebugCheck
    val identity: AwsCredentialsIdentity = credentialsProvider.resolveCredentials()
    val builder = SdkHttpFullRequest
      .builder()
      .method(SdkHttpMethod.fromValue(call.method))
      .uri(call.uri)
    call.headers.foreach { case (k, v) => builder.appendHeader(k, v) }
    val signRequest = SignRequest
      .builder(identity)
      .request(builder.build())
      .payload(ContentStreamProvider.fromByteArray(call.body))
      .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, signingName)
      .putProperty(AwsV4HttpSigner.REGION_NAME, region)
      .putProperty(HttpSigner.SIGNING_CLOCK, clock)
      .build()
    val signed = signer.sign(signRequest)
    val signedHeaders = signed
      .request()
      .headers()
      .asScala
      .toVector
      .flatMap { case (k, vs) => vs.asScala.map(k -> _) }
    call.copy(headers = signedHeaders)

  /** Remove the secret key, session token and any SigV4 signature from `text`. */
  def redact(text: String): String =
    val secrets = Try {
      credentialsProvider.resolveCredentials() match
        case s: AwsSessionCredentials => Vector(s.secretAccessKey(), s.sessionToken())
        case c                        => Vector(c.secretAccessKey())
    }.getOrElse(Vector.empty)
    SigV4Signer.redact(text, secrets)

  override def toString: String = s"SigV4Signer(service=$signingName, region=$region)"

object SigV4Signer:

  val Redacted: String = "[REDACTED]"

  /** SDK logger that writes canonical requests (including session tokens) at DEBUG. */
  val SdkSignerLoggerName: String = "software.amazon.awssdk.http.auth.aws.internal.signer"

  val SdkDebugWarning: String =
    s"DEBUG logging is enabled for $SdkSignerLoggerName; it logs SigV4 canonical requests, " +
      "which contain x-amz-security-token for role credentials. Set software.amazon.awssdk to INFO."

  private val RegionPattern    = "^[a-z]{2}(-[a-z]+)+-\\d+$".r
  private val SignaturePattern = "Signature=[0-9a-fA-F]+".r

  /** AWS region names such as `us-east-2`; also blocks host injection through the region. */
  def isValidRegion(region: String): Boolean = RegionPattern.matches(region)

  /** Replace each non-blank secret and any `Signature=<hex>` with [[Redacted]]. */
  def redact(text: String, secrets: Iterable[String]): String =
    if text == null then ""
    else
      val withoutSecrets = secrets
        .filter(s => s != null && s.nonEmpty)
        .foldLeft(text)((acc, s) => acc.replace(s, Redacted))
      SignaturePattern.replaceAllIn(withoutSecrets, s"Signature=$Redacted")
