package edu.scholarchain.hadoop.io

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.Job

/**
 * Input layout of a source-normalization job, chosen with `-D scholarchain.input.layout=...`:
 *   - `whole-file` (default): every file is one record ([[WholeFileInputFormat]]); the layout of
 *     the fetched Crossref (one work per file), OpenAlex (one API response of up to 50 works per
 *     file) and ORCID (one record per file) directories.
 *   - `json-lines`: one JSON object per line ([[JsonLineInputFormat]]), splittable.
 */
object InputLayout:
  val Key       = "scholarchain.input.layout"
  val WholeFile = "whole-file"
  val JsonLines = "json-lines"

  def layout(conf: Configuration): String =
    Option(conf.get(Key)).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse(WholeFile)

  /** Error text for an unknown layout value, None when valid. */
  def validate(conf: Configuration): Option[String] =
    val l = layout(conf)
    if l == WholeFile || l == JsonLines then None
    else Some(s"Invalid $Key '$l': expected '$WholeFile' or '$JsonLines'")

  def configure(job: Job): Unit =
    if layout(job.getConfiguration) == JsonLines then
      job.setInputFormatClass(classOf[JsonLineInputFormat])
    else job.setInputFormatClass(classOf[WholeFileInputFormat])

  /**
   * True when `input` (file, or directory searched recursively) holds at least one visible file.
   */
  def hasInputFiles(conf: Configuration, input: String): Boolean =
    val p  = new Path(input)
    val fs = p.getFileSystem(conf)
    if !fs.exists(p) then false
    else
      val it    = fs.listFiles(p, true)
      var found = false
      while !found && it.hasNext do
        val n = it.next().getPath.getName
        if !n.startsWith("_") && !n.startsWith(".") then found = true
      found

  /**
   * Common pre-flight of the normalizer tools: validates the layout and that the input holds files.
   * Returns an exit code (2 invalid option, 3 empty or missing input) with a message on stderr, or
   * None to proceed.
   */
  def preflight(conf: Configuration, input: String, dataset: String): Option[Int] =
    validate(conf) match
      case Some(msg) =>
        System.err.println(msg)
        Some(2)
      case None =>
        if hasInputFiles(conf, input) then None
        else
          System.err.println(
            s"No $dataset input files under '$input' (missing or empty directory). " +
              s"Fetch the $dataset records first; nothing was written."
          )
          Some(3)
