package edu.scholarchain.rag

import edu.scholarchain.core.SecretRef
import edu.scholarchain.storage.{HttpCall, HttpTransport, SigV4Signer}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.control.NonFatal

/** A secret could not be resolved. The message names the reference, never a value. */
final class SecretResolutionException(message: String) extends RuntimeException(message)

/**
 * Resolves a [[SecretRef]] to its value. Implementations must never log the value or include it in
 * an exception message.
 */
trait SecretResolver:
  def resolve(ref: SecretRef): String

/**
 * Reads `SecretRef.EnvVarName` from the process environment (never from `.env` files).
 *
 * @param env
 *   injected for tests; defaults to `sys.env.get`
 */
final class EnvSecretResolver(env: String => Option[String] = sys.env.get) extends SecretResolver:

  def resolve(ref: SecretRef): String = ref match
    case SecretRef.EnvVarName(name) =>
      env(name).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw SecretResolutionException(s"environment variable $name is unset or empty")
      }
    case other =>
      throw SecretResolutionException(s"EnvSecretResolver cannot resolve $other")

/**
 * Reads `SecretRef.SecretsManagerArn` with Secrets Manager `GetSecretValue` over SigV4-signed HTTPS
 * (JSON protocol), in the region named by the ARN. `SecretString` is used as is, unless it is a
 * JSON object with an `api_key`, `apiKey` or `key` string field, which is then used.
 */
final class SecretsManagerSecretResolver(
  transport: HttpTransport,
  credentials: AwsCredentialsProvider,
  timeout: Duration = Duration.ofSeconds(10),
  endpointOverride: Option[URI] = None
) extends SecretResolver:

  def resolve(ref: SecretRef): String = ref match
    case SecretRef.SecretsManagerArn(arn) =>
      val region = SecretsManagerSecretResolver.regionOf(arn)
      val signer = SigV4Signer(credentials, region, "secretsmanager")
      val uri =
        endpointOverride.getOrElse(URI.create(s"https://secretsmanager.$region.amazonaws.com/"))
      val call = HttpCall(
        "POST",
        uri,
        Vector(
          "Content-Type" -> "application/x-amz-json-1.1",
          "X-Amz-Target" -> "secretsmanager.GetSecretValue"
        ),
        ujson.write(ujson.Obj("SecretId" -> arn)).getBytes(StandardCharsets.UTF_8)
      )
      val reply =
        try transport.execute(signer.sign(call), Some(timeout))
        catch
          case NonFatal(e) =>
            throw SecretResolutionException(
              s"GetSecretValue for $ref failed: ${e.getClass.getSimpleName}"
            )
      if !reply.isSuccess then
        throw SecretResolutionException(s"GetSecretValue for $ref failed: status=${reply.status}")
      val secretString = scala.util
        .Try(ujson.read(reply.body))
        .toOption
        .flatMap(_.objOpt)
        .flatMap(_.get("SecretString"))
        .flatMap(_.strOpt)
        .getOrElse(throw SecretResolutionException(s"secret $ref has no SecretString"))
      val value = SecretsManagerSecretResolver.extract(secretString).trim
      if value.isEmpty then throw SecretResolutionException(s"secret $ref is empty")
      value
    case other =>
      throw SecretResolutionException(s"SecretsManagerSecretResolver cannot resolve $other")

object SecretsManagerSecretResolver:

  /** Region segment of a Secrets Manager ARN (`arn:aws:secretsmanager:<region>:...`). */
  def regionOf(arn: String): String =
    val parts = arn.split(":")
    if parts.length < 7 || !SigV4Signer.isValidRegion(parts(3)) then
      throw SecretResolutionException("secret ARN has no valid region")
    parts(3)

  private val KeyFields = Vector("api_key", "apiKey", "key")

  /** The key inside a JSON SecretString, or the string itself. */
  def extract(secretString: String): String =
    scala.util
      .Try(ujson.read(secretString))
      .toOption
      .flatMap(_.objOpt)
      .flatMap(o => KeyFields.flatMap(k => o.get(k).flatMap(_.strOpt)).headOption)
      .getOrElse(secretString)

/** Dispatches env-var references and Secrets Manager ARNs to the matching resolver. */
final class CompositeSecretResolver(env: SecretResolver, secretsManager: SecretResolver)
    extends SecretResolver:

  def resolve(ref: SecretRef): String = ref match
    case _: SecretRef.EnvVarName        => env.resolve(ref)
    case _: SecretRef.SecretsManagerArn => secretsManager.resolve(ref)
