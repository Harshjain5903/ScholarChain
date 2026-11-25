package edu.scholarchain.spark

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import scala.util.Try

/**
 * Best-effort reproducibility facts for the pipeline's run manifest: code version, jar checksum and
 * input file checksums. Nothing here may fail a run: an unavailable fact is recorded as `null` with
 * the reason.
 */
object RunManifest:

  /** Environment variable that supplies the code git SHA when `--git-sha` is absent. */
  val GitShaEnv: String = "SCHOLARCHAIN_GIT_SHA"

  /**
   * Environment variable (`true`/`false`) that supplies the dirty flag when `--git-dirty` is
   * absent.
   */
  val GitDirtyEnv: String = "SCHOLARCHAIN_GIT_DIRTY"

  /**
   * Code version of the running pipeline.
   *
   * @param source
   *   where the values came from: `flag`, `env`, `git` or `unavailable`
   */
  final case class CodeVersion(gitSha: Option[String], gitDirty: Option[Boolean], source: String):

    def toJson: ujson.Obj = ujson.Obj(
      "gitSha"   -> gitSha.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "gitDirty" -> gitDirty.fold[ujson.Value](ujson.Null)(ujson.Bool(_)),
      "source"   -> source
    )

  /**
   * Resolve the code version: explicit flags win, then the environment, then a best-effort `git`
   * probe of the driver's working directory. Never throws.
   */
  def resolveCodeVersion(
    flagSha: Option[String],
    flagDirty: Option[Boolean],
    env: Map[String, String],
    probe: () => Option[(String, Boolean)]
  ): CodeVersion =
    val envSha   = env.get(GitShaEnv).map(_.trim).filter(_.nonEmpty)
    val envDirty = env.get(GitDirtyEnv).flatMap(_.trim.toBooleanOption)
    if flagSha.isDefined then CodeVersion(flagSha, flagDirty.orElse(envDirty), "flag")
    else if envSha.isDefined then CodeVersion(envSha, flagDirty.orElse(envDirty), "env")
    else
      Try(probe()).toOption.flatten match
        case Some((sha, dirty)) => CodeVersion(Some(sha), Some(flagDirty.getOrElse(dirty)), "git")
        case None               => CodeVersion(None, flagDirty.orElse(envDirty), "unavailable")

  /** Run a short command; its trimmed stdout on exit code 0 within 10 seconds, otherwise None. */
  private def runCommand(cmd: String*): Option[String] =
    Try {
      val proc = new ProcessBuilder(cmd*).redirectErrorStream(false).start()
      proc.getOutputStream.close()
      val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
      if proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0 then Some(out.trim)
      else
        proc.destroyForcibly()
        None
    }.toOption.flatten

  /** `git rev-parse HEAD` plus a dirty flag from `git status --porcelain`; None if git fails. */
  def gitProbe(): Option[(String, Boolean)] =
    for
      sha    <- runCommand("git", "rev-parse", "HEAD").filter(_.matches("[0-9a-f]{40}"))
      status <- runCommand("git", "status", "--porcelain")
    yield (sha, status.nonEmpty)

  /** Lower-case hex SHA-256 of a stream; the stream is closed. */
  def sha256(in: java.io.InputStream): String =
    try
      val md  = MessageDigest.getInstance("SHA-256")
      val buf = new Array[Byte](1 << 16)
      var n   = in.read(buf)
      while n >= 0 do
        md.update(buf, 0, n)
        n = in.read(buf)
      md.digest().map("%02x".format(_)).mkString
    finally in.close()

  /**
   * SHA-256 of the jar this class was loaded from, when it was loaded from a jar file. Classes run
   * from a directory classpath (sbt, tests) report `null` with the reason.
   */
  def jarInfo(): ujson.Obj =
    val location = Try(
      Option(getClass.getProtectionDomain.getCodeSource).map(_.getLocation.toURI)
    ).toOption.flatten
    location match
      case Some(uri) if uri.getPath != null && uri.getPath.endsWith(".jar") =>
        val path = java.nio.file.Paths.get(uri)
        Try(sha256(java.nio.file.Files.newInputStream(path))).toOption match
          case Some(digest) => ujson.Obj("path" -> path.toString, "sha256" -> digest)
          case None =>
            ujson.Obj("path" -> path.toString, "sha256" -> ujson.Null, "reason" -> "unreadable")
      case other =>
        ujson.Obj(
          "path"   -> other.map(_.toString).fold[ujson.Value](ujson.Null)(ujson.Str(_)),
          "sha256" -> ujson.Null,
          "reason" -> "not running from a jar"
        )

  /** One checksummed input file. */
  final case class InputFile(path: String, sizeBytes: Long, sha256: String):

    def toJson: ujson.Obj =
      ujson.Obj("path" -> path, "sizeBytes" -> sizeBytes.toDouble, "sha256" -> sha256)

  private def hidden(name: String): Boolean = name.startsWith("_") || name.startsWith(".")

  /**
   * SHA-256 of every data file under each root (recursive; Hadoop hidden files such as `_SUCCESS`
   * and `.crc` skipped), sorted by path. Reads every input byte once on the driver through the
   * Hadoop FileSystem, so cost is linear in input size; disable with `--input-checksums false` on
   * very large inputs. A missing root contributes no files.
   */
  def inputFiles(conf: Configuration, roots: Seq[String]): Vector[InputFile] =
    roots.toVector
      .flatMap { root =>
        val raw      = new Path(root)
        val fs       = raw.getFileSystem(conf)
        val rootPath = fs.makeQualified(raw)
        if !fs.exists(rootPath) then Vector.empty
        else
          val it  = fs.listFiles(rootPath, true)
          val buf = Vector.newBuilder[InputFile]
          while it.hasNext do
            val status = it.next()
            val p      = fs.makeQualified(status.getPath)
            // Path components below the root only: a dot-prefixed ancestor of the root is not hidden.
            val parts = Iterator.iterate(p)(_.getParent).takeWhile(x => x != null && x != rootPath)
            if status.isFile && !parts.exists(x => hidden(x.getName)) then
              buf += InputFile(p.toString, status.getLen, sha256(fs.open(p)))
          buf.result()
      }
      .sortBy(_.path)
