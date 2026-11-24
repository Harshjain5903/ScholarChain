package edu.scholarchain.hadoop.driver

import edu.scholarchain.core.{CoauthorPairCandidate, PublicationTeam, WorkRecord}
import edu.scholarchain.hadoop.jobs.GraphExportJob
import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import scala.util.Try
import upickle.default.*

/**
 * Verifies a J08 graph-export directory (the `--hadoop-output` consumed by Spark): every line of
 * `work_records/`, `teams/` and `pair_candidates/` must deserialize as the core type Spark's
 * DataLoader reads, and pair candidates must be canonical (mentionA < mentionB). Each of those
 * required outputs must exist and hold at least one record, and the export root must not contain
 * stray part files (records of unknown type, or the empty default-output files of review finding
 * N49): Spark reads only the per-type subdirectories, so anything at the root is unexpected.
 *
 * Usage: `ExportVerifier <export-dir>`; prints a JSON report and exits 1 on any failure.
 */
object ExportVerifier:

  /** Per-directory verification result. */
  case class DirReport(
    dir: String,
    coreType: String,
    files: Vector[String],
    records: Long,
    parseFailures: Long,
    invariantFailures: Long,
    firstError: Option[String]
  ):
    /** A required output with no part files or no records. */
    def empty: Boolean = files.isEmpty || records == 0

    def ok: Boolean = !empty && parseFailures == 0 && invariantFailures == 0

  /** Whole-export verification result. */
  case class ExportReport(
    exportDir: String,
    dirs: Vector[DirReport],
    unexpectedRootFiles: Vector[String]
  ):
    def ok: Boolean = dirs.forall(_.ok) && unexpectedRootFiles.isEmpty

  private val visible: PathFilter = (p: Path) =>
    val n = p.getName
    !n.startsWith("_") && !n.startsWith(".")

  /** Read all visible, possibly compressed, part files under `dir` as lines. */
  def readLines(dir: Path, conf: Configuration): (Vector[String], Vector[String]) =
    val fs = dir.getFileSystem(conf)
    if !fs.exists(dir) then (Vector.empty, Vector.empty)
    else
      val codecs = new CompressionCodecFactory(conf)
      val files =
        fs.listStatus(dir, visible).toVector.filter(_.isFile).map(_.getPath).sortBy(_.getName)
      val lines = files.flatMap { p =>
        val raw = fs.open(p)
        val in  = Option(codecs.getCodec(p)).map(_.createInputStream(raw)).getOrElse(raw)
        val br  = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        try
          Iterator.continually(br.readLine()).takeWhile(_ != null).filter(_.trim.nonEmpty).toVector
        finally br.close()
      }
      (files.map(_.getName), lines)

  private def check[T: Reader](
    root: Path,
    dirName: String,
    coreType: String,
    conf: Configuration,
    invariant: T => Option[String]
  ): DirReport =
    val (files, lines) = readLines(new Path(root, dirName), conf)
    var parseFailures  = 0L
    var invFailures    = 0L
    var firstError     = Option.empty[String]
    lines.foreach { line =>
      Try(read[T](line)).toEither match
        case Left(e) =>
          parseFailures += 1
          if firstError.isEmpty then firstError = Some(s"parse: ${e.getMessage}")
        case Right(v) =>
          invariant(v).foreach { msg =>
            invFailures += 1
            if firstError.isEmpty then firstError = Some(s"invariant: $msg")
          }
    }
    DirReport(dirName, coreType, files, lines.size.toLong, parseFailures, invFailures, firstError)

  /** Verify the export directory and return one report per Spark-consumed record type. */
  def verify(exportDir: String, conf: Configuration = new Configuration()): Vector[DirReport] =
    val root = new Path(exportDir)
    Vector(
      check[WorkRecord](root, GraphExportJob.WorkRecordsOutput, "WorkRecord", conf, _ => None),
      check[PublicationTeam](
        root,
        GraphExportJob.TeamsOutput,
        "PublicationTeam",
        conf,
        t =>
          if t.teamSize == t.authorMentions.size then None
          else Some(s"teamSize ${t.teamSize} != ${t.authorMentions.size} for ${t.workId}")
      ),
      check[CoauthorPairCandidate](
        root,
        GraphExportJob.PairCandidatesOutput,
        "CoauthorPairCandidate",
        conf,
        p =>
          if p.mentionA < p.mentionB then None
          else Some(s"non-canonical pair ${p.mentionA} / ${p.mentionB}")
      )
    )

  /**
   * Names of the visible files (not directories) directly under the export root, sorted. J08 writes
   * every record type to its own subdirectory, so any such file is unexpected.
   */
  def unexpectedRootFiles(
    exportDir: String,
    conf: Configuration = new Configuration()
  ): Vector[String] =
    val root = new Path(exportDir)
    val fs   = root.getFileSystem(conf)
    if !fs.exists(root) || !fs.getFileStatus(root).isDirectory then Vector.empty
    else fs.listStatus(root, visible).toVector.filter(_.isFile).map(_.getPath.getName).sorted

  /** Verify the per-type outputs and the export root layout. */
  def verifyExport(exportDir: String, conf: Configuration = new Configuration()): ExportReport =
    ExportReport(exportDir, verify(exportDir, conf), unexpectedRootFiles(exportDir, conf))

  def reportJson(report: ExportReport): ujson.Obj =
    ujson.Obj(
      "exportDir"           -> report.exportDir,
      "ok"                  -> report.ok,
      "unexpectedRootFiles" -> ujson.Arr.from(report.unexpectedRootFiles.map(ujson.Str(_))),
      "dirs" -> ujson.Arr.from(report.dirs.map { r =>
        ujson.Obj(
          "dir"               -> r.dir,
          "coreType"          -> r.coreType,
          "files"             -> ujson.Arr.from(r.files.map(ujson.Str(_))),
          "records"           -> r.records.toDouble,
          "parseFailures"     -> r.parseFailures.toDouble,
          "invariantFailures" -> r.invariantFailures.toDouble,
          "empty"             -> r.empty,
          "firstError"        -> r.firstError.map(ujson.Str(_)).getOrElse(ujson.Null),
          "ok"                -> r.ok
        )
      })
    )

  def main(args: Array[String]): Unit =
    if args.length != 1 then
      System.err.println("Usage: ExportVerifier <export-dir>")
      sys.exit(2)
    val report = verifyExport(args(0))
    println(ujson.write(reportJson(report), indent = 2))
    sys.exit(if report.ok then 0 else 1)
