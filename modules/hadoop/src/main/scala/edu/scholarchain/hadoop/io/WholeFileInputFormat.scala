package edu.scholarchain.hadoop.io

import java.util.{ArrayList, List as JList}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{InputSplit, Job, JobContext, RecordReader, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.input.{CombineFileSplit, FileInputFormat}
import scala.jdk.CollectionConverters.*

/**
 * Whole-file input: every input file is one record (key = index of the file within its split, value
 * \= the entire file content). Used for directories of many small JSON documents (one Crossref work
 * or ORCID record per file) and of multi-record API responses (OpenAlex chunk files), where a
 * "line" is not a record.
 *
 * Splits are deterministic: visible files are sorted by path and packed in that order into splits
 * of at most [[MaxSplitBytesKey]] bytes (default 32 MiB) and [[MaxSplitFilesKey]] files (default
 * 2000), so a directory of tens of thousands of files runs as a few map tasks and reruns produce
 * identical part files. Files are never split.
 */
class WholeFileInputFormat extends FileInputFormat[LongWritable, Text]:

  override def isSplitable(context: JobContext, filename: Path): Boolean = false

  override def getSplits(job: JobContext): JList[InputSplit] =
    val conf     = job.getConfiguration
    val maxBytes = conf.getLong(WholeFileInputFormat.MaxSplitBytesKey, 32L * 1024 * 1024)
    val maxFiles = conf.getInt(WholeFileInputFormat.MaxSplitFilesKey, 2000)
    val files =
      listStatus(job).asScala.filter(_.isFile).sortBy(_.getPath.toString).toVector
    val splits = new ArrayList[InputSplit]()
    var paths  = Vector.empty[Path]
    var lens   = Vector.empty[Long]
    def flush(): Unit =
      if paths.nonEmpty then
        splits.add(new CombineFileSplit(paths.toArray, lens.toArray))
        paths = Vector.empty
        lens = Vector.empty
    files.foreach { f =>
      if paths.nonEmpty && (lens.sum + f.getLen > maxBytes || paths.size >= maxFiles) then flush()
      paths = paths :+ f.getPath
      lens = lens :+ f.getLen
    }
    flush()
    splits

  override def createRecordReader(
    split: InputSplit,
    context: TaskAttemptContext
  ): RecordReader[LongWritable, Text] = new WholeFileRecordReader

object WholeFileInputFormat:
  val MaxSplitBytesKey = "scholarchain.input.whole-file.max-split-bytes"
  val MaxSplitFilesKey = "scholarchain.input.whole-file.max-split-files"

  /** Counter incremented once per file read. */
  val InputFilesCounter = "input_files"

class WholeFileRecordReader extends RecordReader[LongWritable, Text]:
  private var split: CombineFileSplit     = scala.compiletime.uninitialized
  private var context: TaskAttemptContext = scala.compiletime.uninitialized
  private var index                       = -1
  private val key                         = new LongWritable()
  private val value                       = new Text()

  override def initialize(s: InputSplit, c: TaskAttemptContext): Unit =
    split = s.asInstanceOf[CombineFileSplit]
    context = c

  override def nextKeyValue(): Boolean =
    index += 1
    if index >= split.getNumPaths then false
    else
      val path = split.getPath(index)
      val fs   = path.getFileSystem(context.getConfiguration)
      val len  = split.getLength(index).toInt
      val buf  = new Array[Byte](len)
      val in   = fs.open(path)
      try org.apache.hadoop.io.IOUtils.readFully(in, buf, 0, len)
      finally in.close()
      key.set(index.toLong)
      value.set(buf)
      context
        .getCounter(
          edu.scholarchain.hadoop.counters.HadoopCounters.SharedGroup,
          WholeFileInputFormat.InputFilesCounter
        )
        .increment(1)
      true

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text       = value

  override def getProgress: Float =
    if split == null || split.getNumPaths == 0 then 1.0f
    else math.min(1.0f, (index + 1).toFloat / split.getNumPaths)
  override def close(): Unit = ()
