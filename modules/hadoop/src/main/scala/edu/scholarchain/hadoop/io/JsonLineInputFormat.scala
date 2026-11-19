package edu.scholarchain.hadoop.io

import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat

/**
 * InputFormat for JSON Lines files (one JSON object per line).
 * Extends the standard TextInputFormat since each line is a complete
 * record. This class exists to provide a named type for job
 * configuration and documentation.
 *
 * JSON Lines format: each line is a valid JSON object terminated by
 * newline. No array wrapper, no trailing commas.
 */
class JsonLineInputFormat extends TextInputFormat
