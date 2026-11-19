package edu.scholarchain.hadoop.io

import edu.scholarchain.core.Provenance
import edu.scholarchain.hadoop.model.RejectRecord
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import upickle.default.*

/**
 * Utility for writing rejected records to a side output using Hadoop's
 * MultipleOutputs. All rejects are serialized as JSON lines to a
 * named output called "rejects".
 */
object RejectWriter:

  val RejectOutputName = "rejects"

  /**
   * Write a reject record to the side output.
   *
   * @param mos            the MultipleOutputs instance
   * @param sourceRecordId the source record that was rejected
   * @param reasonCode     machine-readable reason code
   * @param reasonMessage  human-readable explanation
   * @param originalContent the original raw content that failed
   * @param jobName        the name of the job that rejected it
   * @param provenance     provenance metadata
   */
  def writeReject(
      mos: MultipleOutputs[?, ?],
      sourceRecordId: String,
      reasonCode: String,
      reasonMessage: String,
      originalContent: String,
      jobName: String,
      provenance: Provenance,
  ): Unit =
    val record = RejectRecord(
      sourceRecordId = sourceRecordId,
      reasonCode = reasonCode,
      reasonMessage = reasonMessage,
      originalContent =
        originalContent.take(4096), // truncate large records
      jobName = jobName,
      provenance = provenance,
    )
    val json = write(record)
    mos.write(
      RejectOutputName,
      new Text(json),
      NullWritable.get(),
    )
