package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.{IdGen, SourceType}

/**
 * Work-ID construction for the Hadoop source parsers (review finding N50).
 *
 * Source records carry a source-qualified provenance ID such as `dblp:<key>` or `openalex:W123`,
 * while core `IdGen.workId` itself prefixes `work:<source>:`. Passing the qualified ID straight
 * through produced doubled IDs such as `work:dblp:dblp:<key>`. This helper strips one leading
 * `<source>:` segment so the result is `work:<source>:<key>`; the provenance `sourceRecordId` keeps
 * its qualified form.
 */
object WorkIds:

  /** Lowercase source segment that `IdGen.workId` writes for `source`. */
  def sourceSegment(source: SourceType): String = source.toString.toLowerCase

  /** The bare source key: `sourceRecordId` without one leading `<source>:` qualifier. */
  def bareKey(source: SourceType, sourceRecordId: String): String =
    sourceRecordId.stripPrefix(s"${sourceSegment(source)}:")

  /** `work:<source>:<bare key>` for a source-qualified or bare source record ID. */
  def workId(source: SourceType, sourceRecordId: String): String =
    IdGen.workId(source, bareKey(source, sourceRecordId))

  /** True when `workId` repeats its source segment, for example `work:dblp:dblp:...`. */
  def hasDoubledSourceSegment(workId: String): Boolean =
    SourceType.values.exists { s =>
      val seg = sourceSegment(s)
      workId.startsWith(s"work:$seg:$seg:")
    }
