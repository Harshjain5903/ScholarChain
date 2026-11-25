package edu.scholarchain.entityresolution

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * File helpers for the gold-set tooling: minimal RFC 4180 CSV reading/writing, SHA-256 hashing and
 * reading Hadoop-style output directories (plain or gzip part files).
 */
object GoldSetIO:

  /** Quote a CSV field when it contains a delimiter, quote, line break or edge whitespace. */
  def escapeField(s: String): String =
    val needsQuotes =
      s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') || s != s.trim
    if needsQuotes then "\"" + s.replace("\"", "\"\"") + "\"" else s

  /** Render a header plus rows as CSV text with `\n` line endings. */
  def writeCsv(header: Vector[String], rows: Vector[Vector[String]]): String =
    (header +: rows).map(_.map(escapeField).mkString(",")).mkString("", "\n", "\n")

  /**
   * Parse CSV text into rows of fields. Handles quoted fields with embedded commas, doubled quotes
   * and line breaks, CRLF line endings and a leading UTF-8 BOM (spreadsheet exports). Blank lines
   * are skipped.
   */
  def parseCsv(text: String): Vector[Vector[String]] =
    val s        = text.stripPrefix("\uFEFF")
    val rows     = Vector.newBuilder[Vector[String]]
    var row      = Vector.newBuilder[String]
    var rowSize  = 0
    val field    = new StringBuilder
    var inQuotes = false
    var quoted   = false
    var i        = 0

    def endField(): Unit =
      row += field.toString
      rowSize += 1
      field.clear()

    def endRow(): Unit =
      endField()
      val r = row.result()
      if !(r.size == 1 && r.head.isEmpty && !quoted) then rows += r
      row = Vector.newBuilder[String]
      rowSize = 0
      quoted = false

    while i < s.length do
      val c = s.charAt(i)
      if inQuotes then
        if c == '"' then
          if i + 1 < s.length && s.charAt(i + 1) == '"' then
            field.append('"')
            i += 1
          else inQuotes = false
        else field.append(c)
      else
        c match
          case '"' =>
            inQuotes = true
            quoted = true
          case ',' => endField()
          case '\r' =>
            if i + 1 < s.length && s.charAt(i + 1) == '\n' then i += 1
            endRow()
          case '\n'  => endRow()
          case other => field.append(other)
      i += 1

    if inQuotes then throw IllegalArgumentException("Unterminated quoted field in CSV input")
    if field.nonEmpty || rowSize > 0 || quoted then endRow()
    rows.result()

  /**
   * Parse CSV text with a header row into (header, rows-as-maps). Short rows are padded with empty
   * strings; duplicate header names are rejected.
   */
  def readTable(text: String): (Vector[String], Vector[Map[String, String]]) =
    val all = parseCsv(text)
    require(all.nonEmpty, "CSV input is empty (no header row)")
    val header = all.head.map(_.trim)
    val dupes  = header.groupBy(identity).collect { case (h, v) if v.size > 1 => h }
    require(dupes.isEmpty, s"Duplicate CSV header columns: ${dupes.toVector.sorted.mkString(", ")}")
    val rows = all.tail.map { r =>
      header.zipWithIndex.map((h, i) => h -> (if i < r.size then r(i) else "")).toMap
    }
    (header, rows)

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  def sha256Hex(s: String): String = sha256Hex(s.getBytes(StandardCharsets.UTF_8))

  def sha256File(file: Path): String = sha256Hex(Files.readAllBytes(file))

  /** Data files of a Hadoop-style output directory (skips `_SUCCESS`, `.crc`, hidden files). */
  def dataFiles(dir: Path): Vector[Path] =
    require(Files.isDirectory(dir), s"Not a directory: $dir")
    Using.resource(Files.list(dir)) { s =>
      s.iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filterNot { p =>
          val n = p.getFileName.toString
          n.startsWith("_") || n.startsWith(".") || n.endsWith(".crc")
        }
        .toVector
        .sortBy(_.getFileName.toString)
    }

  /** Non-blank lines of a plain or `.gz` UTF-8 file. */
  def readLines(file: Path): Vector[String] =
    val raw = Files.newInputStream(file)
    val in  = if file.getFileName.toString.endsWith(".gz") then GZIPInputStream(raw) else raw
    Using.resource(BufferedReader(InputStreamReader(in, StandardCharsets.UTF_8))) { r =>
      Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.trim.nonEmpty).toVector
    }

  def writeString(file: Path, content: String): Unit =
    Option(file.getParent).foreach(Files.createDirectories(_))
    Files.writeString(file, content, StandardCharsets.UTF_8)
    ()

  /**
   * Gold-set artifacts (candidate CSVs, labeled CSVs, evaluation reports) must never be written
   * into the source tree's `modules/` directory. Returns Left when any component of the absolute,
   * normalized path is named `modules`.
   */
  def outsideModules(path: Path): Either[String, Path] =
    val abs = path.toAbsolutePath.normalize
    val hit = abs.iterator().asScala.exists(_.toString == "modules")
    if hit then Left(s"Refusing to write gold-set output inside a 'modules' directory: $abs")
    else Right(abs)

  def optNum(d: Option[Double]): ujson.Value = d.map(ujson.Num(_)).getOrElse(ujson.Null)

  def optStr(s: Option[String]): ujson.Value = s.map(ujson.Str(_)).getOrElse(ujson.Null)
