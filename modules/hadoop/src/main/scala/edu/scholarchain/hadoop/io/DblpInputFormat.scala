package edu.scholarchain.hadoop.io

import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{InputSplit, RecordReader, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat

/**
 * Custom InputFormat for DBLP XML that uses XmlRecordReader to emit complete XML records (article,
 * inproceedings, proceedings, book, incollection, phdthesis, mastersthesis) without splitting them
 * across mappers.
 *
 * The input format is not splittable -- each file is processed by exactly one mapper to guarantee
 * record integrity.
 */
class DblpInputFormat extends FileInputFormat[LongWritable, Text]:

  /** DBLP XML record types recognized by the pipeline. */
  private val recordTypes = Vector(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis"
  )

  override def isSplitable(
    context: org.apache.hadoop.mapreduce.JobContext,
    filename: org.apache.hadoop.fs.Path
  ): Boolean = false

  override def createRecordReader(
    split: InputSplit,
    context: TaskAttemptContext
  ): RecordReader[LongWritable, Text] =
    val conf = context.getConfiguration
    // Set the start/end tags for the XmlRecordReader
    val startTags =
      recordTypes.map(t => s"<$t").mkString(",")
    val endTags =
      recordTypes.map(t => s"</$t>").mkString(",")
    conf.set("scholarchain.xml.start.tags", startTags)
    conf.set("scholarchain.xml.end.tags", endTags)
    new XmlRecordReader()
