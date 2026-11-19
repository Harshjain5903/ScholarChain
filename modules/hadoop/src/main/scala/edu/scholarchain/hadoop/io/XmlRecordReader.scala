package edu.scholarchain.hadoop.io

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FileSystem, Path}
import org.apache.hadoop.io.{DataOutputBuffer, LongWritable, Text}
import org.apache.hadoop.mapreduce.{InputSplit, RecordReader, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.input.{FileSplit, TextInputFormat}

/**
 * A RecordReader that reads complete XML records delimited by configurable start and end tags. It
 * scans the byte stream for matching tag pairs and emits each complete element (including the tags)
 * as a single Text value.
 *
 * This prevents DBLP records from being split across mappers.
 *
 * Configuration keys:
 *   - scholarchain.xml.start.tags: comma-separated start tags (e.g. "<article,<inproceedings")
 *   - scholarchain.xml.end.tags: comma-separated end tags (e.g. "</article>,</inproceedings>")
 */
class XmlRecordReader extends RecordReader[LongWritable, Text]:

  private var start: Long                   = 0L
  private var end: Long                     = 0L
  private var pos: Long                     = 0L
  private var fsin: FSDataInputStream       = scala.compiletime.uninitialized
  private var startTags: Array[Array[Byte]] = scala.compiletime.uninitialized
  private var endTags: Array[Array[Byte]]   = scala.compiletime.uninitialized
  private val buffer                        = new DataOutputBuffer()
  private val key                           = new LongWritable()
  private val value                         = new Text()

  override def initialize(
    split: InputSplit,
    context: TaskAttemptContext
  ): Unit =
    val fileSplit = split.asInstanceOf[FileSplit]
    val conf      = context.getConfiguration
    val path      = fileSplit.getPath
    val fs        = path.getFileSystem(conf)

    start = fileSplit.getStart
    end = start + fileSplit.getLength

    val startTagStr =
      conf.get("scholarchain.xml.start.tags", "<article,<inproceedings")
    val endTagStr =
      conf.get(
        "scholarchain.xml.end.tags",
        "</article>,</inproceedings>"
      )

    startTags = startTagStr.split(",").map(_.getBytes("UTF-8"))
    endTags = endTagStr.split(",").map(_.getBytes("UTF-8"))

    fsin = fs.open(path)
    fsin.seek(start)
    pos = start

  override def nextKeyValue(): Boolean =
    if pos >= end then return false
    // Scan for the next start tag
    val foundStart = scanToTag(startTags, matchStart = true)
    if !foundStart then return false
    // Now scan for the matching end tag
    val startTagIndex = findMatchingTagIndex()
    val foundEnd =
      if startTagIndex >= 0 && startTagIndex < endTags.length
      then scanToTag(Array(endTags(startTagIndex)), matchStart = false)
      else scanToTag(endTags, matchStart = false)
    if foundEnd then
      key.set(pos)
      value.set(buffer.getData, 0, buffer.getLength)
      true
    else false

  /**
   * Determine which start tag was matched by examining the buffer contents.
   */
  private def findMatchingTagIndex(): Int =
    if buffer.getLength == 0 then return -1
    val data = new String(buffer.getData, 0, buffer.getLength, "UTF-8")
    startTags.indexWhere { tag =>
      data.startsWith(new String(tag, "UTF-8"))
    }

  /**
   * Scan the input stream looking for one of the given tags. If matchStart is true, the buffer is
   * reset before scanning; if false, we append the end tag to the existing buffer content.
   */
  private def scanToTag(
    tags: Array[Array[Byte]],
    matchStart: Boolean
  ): Boolean =
    if matchStart then buffer.reset()
    val matchers = tags.map(t => new TagMatcher(t))
    var found    = false
    while !found && pos < end do
      val b = fsin.read()
      if b == -1 then return false
      pos += 1
      if matchStart then
        // Before finding start tag, only feed matchers
        matchers.foreach(_.feed(b.toByte))
        if matchers.exists(_.matched) then
          // Write the matched tag into buffer
          val idx = matchers.indexWhere(_.matched)
          buffer.write(tags(idx), 0, tags(idx).length)
          found = true
      else
        buffer.write(b)
        matchers.foreach(_.feed(b.toByte))
        if matchers.exists(_.matched) then found = true
    found

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text       = value

  override def getProgress: Float =
    if start == end then 1.0f
    else (pos - start).toFloat / (end - start).toFloat

  override def close(): Unit =
    if fsin != null then fsin.close()

/**
 * Simple state machine that matches a byte sequence in a stream.
 */
private class TagMatcher(tag: Array[Byte]):
  private var idx: Int = 0
  var matched: Boolean = false

  def feed(b: Byte): Unit =
    if matched then return
    if b == tag(idx) then
      idx += 1
      if idx == tag.length then matched = true
    else if b == tag(0) then idx = 1
    else idx = 0

  def reset(): Unit =
    idx = 0
    matched = false
