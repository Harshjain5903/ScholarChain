package edu.scholarchain.services

import ch.qos.logback.classic.{Level, LoggerContext, Logger => LogbackLogger}
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.net.URL
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/**
 * Feature tests for the production runtime logging config
 * (modules/services/src/main/resources/logback.xml).
 *
 * Security requirement: the AWS SDK v2 SigV4 signer logs the canonical request, including
 * x-amz-security-token, at DEBUG. No root-level override may let that reach CloudWatch.
 *
 * Each test loads the production file into its own isolated LoggerContext through JoranConfigurator
 * and stops it afterwards, so the global SLF4J context used by other suites is never mutated.
 */
class LoggingConfigSpec extends AnyFlatSpec with Matchers:

  private val LevelVar      = "SCHOLARCHAIN_LOG_LEVEL"
  private val SignerLogger  = "software.amazon.awssdk.http.auth.aws.internal.signer"
  private val SignerClass   = s"$SignerLogger.DefaultV4RequestSigner"
  private val FakeTokenLine = "x-amz-security-token:FAKE_TOKEN"

  private val SecretBearingLoggers = Seq(
    "software.amazon.awssdk",
    "software.amazon.awssdk.request",
    SignerLogger,
    SignerClass,
    "software.amazon.awssdk.services.s3",
    "org.apache.http.wire",
    "org.apache.http.headers",
    "io.netty",
    "io.netty.handler.logging.LoggingHandler"
  )

  private def productionConfig: URL =
    val url = getClass.getClassLoader.getResource("logback.xml")
    url should not be null
    url

  /** Load the production logback.xml into a fresh context, run `f`, then stop the context. */
  private def withProductionContext[A](rootLevelProperty: Option[String])(
    f: LoggerContext => A
  ): A =
    val ctx = new LoggerContext()
    ctx.setName("logging-config-spec")
    // A standalone context has no MDC adapter (normally installed by the SLF4J provider).
    ctx.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter())
    rootLevelProperty.foreach(ctx.putProperty(LevelVar, _))
    try
      val configurator = new JoranConfigurator()
      configurator.setContext(ctx)
      configurator.doConfigure(productionConfig)
      f(ctx)
    finally ctx.stop()

  private def root(ctx: LoggerContext): LogbackLogger =
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

  "The services classpath" should "resolve logback.xml to the services module's production file" in {
    val path = productionConfig.toString
    path should include("modules/services")
    path should not include "test-classes"
  }

  "The production logback.xml" should "default the root level to INFO" in {
    assume(
      sys.env.get(LevelVar).isEmpty && sys.props.get(LevelVar).isEmpty,
      s"$LevelVar is set in this environment; default cannot be observed"
    )
    withProductionContext(None) { ctx =>
      root(ctx).getLevel shouldBe Level.INFO
    }
  }

  it should "honour SCHOLARCHAIN_LOG_LEVEL for the root logger" in {
    withProductionContext(Some("DEBUG")) { ctx =>
      root(ctx).getLevel shouldBe Level.DEBUG
      ctx.getLogger("edu.scholarchain.services.Routes").isDebugEnabled shouldBe true
    }
  }

  it should "keep AWS SDK, HTTP wire and Netty loggers at WARN when root is overridden to DEBUG" in {
    withProductionContext(Some("DEBUG")) { ctx =>
      root(ctx).isDebugEnabled shouldBe true
      SecretBearingLoggers.foreach { name =>
        withClue(s"logger $name: ") {
          val logger = ctx.getLogger(name)
          logger.isDebugEnabled shouldBe false
          logger.isInfoEnabled shouldBe false
          logger.isWarnEnabled shouldBe true
        }
      }
    }
  }

  it should "keep AWS SDK loggers at WARN when root is set to DEBUG programmatically" in {
    withProductionContext(None) { ctx =>
      root(ctx).setLevel(Level.DEBUG)
      root(ctx).isDebugEnabled shouldBe true
      ctx.getLogger("software.amazon.awssdk").isDebugEnabled shouldBe false
      ctx.getLogger(SignerClass).isDebugEnabled shouldBe false
    }
  }

  it should "not emit a DEBUG signer message containing x-amz-security-token" in {
    withProductionContext(Some("DEBUG")) { ctx =>
      val captured = new ListAppender[ILoggingEvent]()
      captured.setContext(ctx)
      captured.start()
      root(ctx).addAppender(captured)

      val signer = ctx.getLogger(SignerClass)
      signer.debug(s"AWS4 Canonical Request: GET\n/\n\nhost:example\n$FakeTokenLine\n")
      ctx.getLogger(SignerLogger).debug(s"String to sign: $FakeTokenLine")
      ctx.getLogger("software.amazon.awssdk.request").debug(s"Sending Request: $FakeTokenLine")
      // Sanity: the appender is live and WARN still passes for the SDK.
      signer.warn("sdk-warn-sentinel")
      ctx.getLogger("edu.scholarchain.services.Routes").debug("app-debug-sentinel")

      val messages = captured.list.asScala.map(_.getFormattedMessage).toList
      messages.exists(_.contains("FAKE_TOKEN")) shouldBe false
      messages should contain("sdk-warn-sentinel")
      messages should contain("app-debug-sentinel")
    }
  }

  it should "encode console output as one JSON object per line" in {
    withProductionContext(None) { ctx =>
      val appender = root(ctx).getAppender("JSON_STDOUT")
      appender should not be null
      val encoder = appender.asInstanceOf[ConsoleAppender[ILoggingEvent]].getEncoder

      val captured = new ListAppender[ILoggingEvent]()
      captured.setContext(ctx)
      captured.start()
      root(ctx).addAppender(captured)
      ctx.getLogger("edu.scholarchain.services.Routes").info("hello {}", "world")

      val line = new String(encoder.encode(captured.list.get(0)), StandardCharsets.UTF_8)
      line.trim.linesIterator.size shouldBe 1
      val json = ujson.read(line)
      json("level").str shouldBe "INFO"
      json("loggerName").str shouldBe "edu.scholarchain.services.Routes"
      json("formattedMessage").str shouldBe "hello world"
    }
  }

  "The global SLF4J context on the services classpath" should
    "auto-configure the AWS SDK signer logger below DEBUG" in {
      // Read-only: verifies the auto-discovered config, without mutating the global context.
      LoggerFactory.getLogger(getClass) // force SLF4J initialisation
      LoggerFactory.getILoggerFactory match
        case ctx: LoggerContext =>
          ctx.getLogger(SignerClass).isDebugEnabled shouldBe false
          ctx.getLogger("software.amazon.awssdk").isDebugEnabled shouldBe false
        case other =>
          cancel(s"SLF4J not bound to logback yet: ${other.getClass.getName}")
    }
