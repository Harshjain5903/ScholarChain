package edu.scholarchain.storage

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}

import java.time.{Clock, Duration, Instant, ZoneOffset}
import scala.collection.mutable

/** Test-only AWS fixtures: dummy static credentials and fake transports. No network, no real keys. */
object StorageTestAws:

  val DummyAccessKey: String = "AKIDEXAMPLEDUMMY0001"
  // Dummy value used to prove the secret never leaks into logs or exceptions.
  val DummySecretKey: String = "dummySecretKeyValue0123456789abcdefDUMMY"

  val dummyCredentials: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create(DummyAccessKey, DummySecretKey))

  /** Fixed signing time 2026-11-22T12:00:00Z, so the credential scope date is 20261122. */
  val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC)
  val ScopeDate: String = "20261122"

  /** Transport that fails the test if anything tries to send a request. */
  object NoNetwork extends HttpTransport:
    def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
      throw AssertionError(s"unexpected network call to ${call.uri}")

  /** Records every request and answers from a queue of canned replies (the last one repeats). */
  final class RecordingTransport(replies: HttpReply*) extends HttpTransport:
    require(replies.nonEmpty, "at least one canned reply")
    val calls: mutable.ArrayBuffer[HttpCall] = mutable.ArrayBuffer.empty
    private var remaining                    = replies.toList

    def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
      calls += call
      remaining match
        case head :: Nil  => head
        case head :: tail => remaining = tail; head
        case Nil          => throw AssertionError("no canned reply")

  def ok(body: String = ""): HttpReply = HttpReply(200, Map.empty, body)
