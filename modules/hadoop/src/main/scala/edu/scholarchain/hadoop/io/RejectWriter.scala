package edu.scholarchain.hadoop.io

import edu.scholarchain.core.Provenance
import edu.scholarchain.hadoop.model.RejectRecord
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import upickle.default.*

/**
 * Utility for writing rejected records to a side output using Hadoop's MultipleOutputs. All rejects
 * are serialized as JSON lines to a named output called "rejects", written under the `_rejects/`
 * subdirectory of the job output (files `_rejects/part-m-NNNNN`).
 *
 * The leading underscore makes Hadoop's and Spark's FileInputFormat hidden-file filter skip the
 * rejects when the job output directory is consumed downstream, so reject lines are never parsed as
 * accepted records (e.g. as WorkRecord). Read rejects explicitly from `<output>/_rejects`.
 */
object RejectWriter:

  val RejectOutputName = "rejects"

  /** Subdirectory (relative to the job output directory) that holds reject files. */
  val RejectDirectory = "_rejects"

  /** MultipleOutputs base output path for rejects. */
  val RejectBaseOutputPath = s"$RejectDirectory/part"

  /**
   * Write a reject record to the side output.
   *
   * @param mos
   *   the MultipleOutputs instance
   * @param sourceRecordId
   *   the source record that was rejected
   * @param reasonCode
   *   machine-readable reason code
   * @param reasonMessage
   *   human-readable explanation
   * @param originalContent
   *   the original raw content that failed
   * @param jobName
   *   the name of the job that rejected it
   * @param provenance
   *   provenance metadata
   */
  def writeReject(
    mos: MultipleOutputs[?, ?],
    sourceRecordId: String,
    reasonCode: String,
    reasonMessage: String,
    originalContent: String,
    jobName: String,
    provenance: Provenance
  ): Unit =
    val record = RejectRecord(
      sourceRecordId = sourceRecordId,
      reasonCode = reasonCode,
      reasonMessage = reasonMessage,
      originalContent = originalContent.take(4096), // truncate large records
      jobName = jobName,
      provenance = provenance
    )
    val json = write(record)
    mos.write(
      RejectOutputName,
      new Text(json),
      NullWritable.get(),
      RejectBaseOutputPath
    )
