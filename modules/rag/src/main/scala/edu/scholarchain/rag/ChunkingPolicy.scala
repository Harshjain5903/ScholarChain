package edu.scholarchain.rag

enum ChunkingStrategy:
  case SentenceBoundary, ParagraphBoundary, FixedWindow

case class ChunkingConfig(
    maxTokens: Int,
    overlapTokens: Int,
    strategy: ChunkingStrategy
)

object ChunkingPolicy:

  val DefaultAbstractPolicy: ChunkingConfig =
    ChunkingConfig(512, 64, ChunkingStrategy.SentenceBoundary)

  val DefaultFullTextPolicy: ChunkingConfig =
    ChunkingConfig(256, 32, ChunkingStrategy.ParagraphBoundary)

  def estimateTokens(text: String): Int =
    if text.isEmpty then 0
    else (text.split("\\s+").length * 1.3).toInt

  def chunk(text: String, config: ChunkingConfig): List[String] =
    if text.isEmpty then return List.empty
    val estimated = estimateTokens(text)
    if estimated <= config.maxTokens then return List(text)

    config.strategy match
      case ChunkingStrategy.SentenceBoundary =>
        chunkBySentence(text, config)
      case ChunkingStrategy.ParagraphBoundary =>
        chunkByParagraph(text, config)
      case ChunkingStrategy.FixedWindow =>
        chunkByFixedWindow(text, config)

  private def chunkBySentence(
      text: String,
      config: ChunkingConfig
  ): List[String] =
    val sentences = text.split("(?<=\\.)\\s+").toList
    groupUnits(sentences, config)

  private def chunkByParagraph(
      text: String,
      config: ChunkingConfig
  ): List[String] =
    val paragraphs = text.split("\n\n").toList.filter(_.nonEmpty)
    groupUnits(paragraphs, config)

  private def chunkByFixedWindow(
      text: String,
      config: ChunkingConfig
  ): List[String] =
    val words = text.split("\\s+").toList
    val windowSize = math.max(1, (config.maxTokens / 1.3).toInt)
    val stepSize =
      math.max(1, windowSize - (config.overlapTokens / 1.3).toInt)
    words.sliding(windowSize, stepSize).map(_.mkString(" ")).toList

  private def groupUnits(
      units: List[String],
      config: ChunkingConfig
  ): List[String] =
    val result = scala.collection.mutable.ListBuffer.empty[String]
    var current = new StringBuilder
    var currentTokens = 0
    for unit <- units do
      val unitTokens = estimateTokens(unit)
      if currentTokens + unitTokens > config.maxTokens && current.nonEmpty then
        result += current.toString.trim
        val overlapText = current.toString
          .split("\\s+")
          .takeRight((config.overlapTokens / 1.3).toInt)
          .mkString(" ")
        current = new StringBuilder(overlapText)
        currentTokens = estimateTokens(overlapText)
      if current.nonEmpty && !current.toString.endsWith(" ") then
        current.append(" ")
      current.append(unit)
      currentTokens += unitTokens
    if current.nonEmpty then result += current.toString.trim
    result.toList
