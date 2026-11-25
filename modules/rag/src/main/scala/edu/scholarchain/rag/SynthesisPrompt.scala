package edu.scholarchain.rag

import edu.scholarchain.core.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.matching.Regex

/**
 * Validates JSON against the subset of JSON Schema (draft-07) used by the prompt schemas: `type`
 * (object, array, string, number, integer, boolean), `required`, `properties`,
 * `additionalProperties: false`, `items`, `minItems`, `maxItems`, `minLength`, `maxLength`,
 * `minimum`, `maximum`. Any other keyword is rejected at construction so the schema can never be
 * silently under-enforced.
 */
final class JsonSchemaValidator(schema: ujson.Value):

  private val Supported = Set(
    "$schema",
    "$id",
    "title",
    "description",
    "type",
    "required",
    "properties",
    "additionalProperties",
    "items",
    "minItems",
    "maxItems",
    "minLength",
    "maxLength",
    "minimum",
    "maximum"
  )

  private def checkKeywords(s: ujson.Value, path: String): Unit =
    val o = s.obj
    o.keys.foreach { k =>
      require(Supported.contains(k), s"unsupported schema keyword '$k' at $path")
    }
    o.get("properties").foreach(_.obj.foreach((k, v) => checkKeywords(v, s"$path.$k")))
    o.get("items").foreach(checkKeywords(_, s"$path[]"))
    o.get("additionalProperties").foreach { v =>
      require(v == ujson.False, s"only additionalProperties=false is supported at $path")
    }

  checkKeywords(schema, "$")

  /** Every violation found, with a JSON path; empty means valid. */
  def validate(value: ujson.Value): Vector[String] = check(schema, value, "$")

  private def check(s: ujson.Value, v: ujson.Value, path: String): Vector[String] =
    val o                              = s.obj
    def num(k: String): Option[Double] = o.get(k).map(_.num)
    val typeErrors = o.get("type").map(_.str) match
      case Some("object") if !v.isInstanceOf[ujson.Obj]   => Vector(s"$path: expected object")
      case Some("array") if !v.isInstanceOf[ujson.Arr]    => Vector(s"$path: expected array")
      case Some("string") if !v.isInstanceOf[ujson.Str]   => Vector(s"$path: expected string")
      case Some("boolean") if !v.isInstanceOf[ujson.Bool] => Vector(s"$path: expected boolean")
      case Some("number") if !v.isInstanceOf[ujson.Num]   => Vector(s"$path: expected number")
      case Some("integer") if !v.numOpt.exists(d => d == math.rint(d)) =>
        Vector(s"$path: expected integer")
      case _ => Vector.empty
    if typeErrors.nonEmpty then typeErrors
    else
      v match
        case obj: ujson.Obj =>
          val props    = o.get("properties").map(_.obj).getOrElse(collection.mutable.Map.empty)
          val required = o.get("required").map(_.arr.map(_.str).toVector).getOrElse(Vector.empty)
          val missing  = required.filterNot(obj.value.contains).map(k => s"$path: missing '$k'")
          val extra =
            if o.get("additionalProperties").contains(ujson.False) then
              obj.value.keys.filterNot(props.contains).map(k => s"$path: unexpected '$k'").toVector
            else Vector.empty
          val nested = obj.value.toVector.flatMap { (k, child) =>
            props.get(k).map(ps => check(ps, child, s"$path.$k")).getOrElse(Vector.empty)
          }
          missing ++ extra ++ nested
        case arr: ujson.Arr =>
          val n = arr.value.size
          val bounds =
            num("minItems").filter(n < _).map(m => s"$path: fewer than ${m.toInt} items") ++
              num("maxItems").filter(n > _).map(m => s"$path: more than ${m.toInt} items")
          val nested = o
            .get("items")
            .map(is =>
              arr.value.toVector.zipWithIndex.flatMap((c, i) => check(is, c, s"$path[$i]"))
            )
            .getOrElse(Vector.empty)
          bounds.toVector ++ nested
        case ujson.Str(str) =>
          (num("minLength").filter(str.length < _).map(m => s"$path: shorter than ${m.toInt}") ++
            num("maxLength")
              .filter(str.length > _)
              .map(m => s"$path: longer than ${m.toInt}")).toVector
        case ujson.Num(d) =>
          (num("minimum").filter(d < _).map(m => s"$path: below $m") ++
            num("maximum").filter(d > _).map(m => s"$path: above $m")).toVector
        case _ => Vector.empty

/**
 * The versioned AnswerSynthesisAgent prompt (`prompts/runtime/answer-synthesis-v2.txt`) and output
 * schema (`prompts/schemas/answer-synthesis-v2.json`), loaded from the classpath copies under
 * `modules/rag/src/main/resources/prompts/`. A test pins the copies byte-for-byte to the files
 * under `prompts/`.
 *
 * Untrusted text (the question, researcher names, every evidence item) is escaped (`&`, `<`, `>`)
 * and placed inside tags, so it cannot close a delimiter or impersonate the instructions.
 */
object SynthesisPrompt:

  val Version: String    = "answer-synthesis-v2"
  val PromptPath: String = s"prompts/runtime/$Version.txt"
  val SchemaPath: String = s"prompts/schemas/$Version.json"

  private val SystemMarker = "===SYSTEM===\n"
  private val UserMarker   = "===USER===\n"

  private def resource(path: String): String =
    val in = Option(getClass.getClassLoader.getResourceAsStream(path))
      .getOrElse(throw IllegalStateException(s"missing classpath resource $path"))
    try String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  /** Raw prompt file text. */
  lazy val templateText: String = resource(PromptPath)

  /** Raw schema file text. */
  lazy val schemaText: String = resource(SchemaPath)

  lazy val schema: JsonSchemaValidator = JsonSchemaValidator(ujson.read(schemaText))

  /** SHA-256 of prompt file plus schema file, recorded in audit records. */
  lazy val hash: String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest((templateText + "\u0000" + schemaText).getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  private lazy val sections: (String, String) =
    require(templateText.startsWith(SystemMarker), s"$PromptPath must start with $SystemMarker")
    val idx = templateText.indexOf(UserMarker)
    require(idx > 0, s"$PromptPath has no $UserMarker section")
    (
      templateText.substring(SystemMarker.length, idx).trim,
      templateText.substring(idx + UserMarker.length)
    )

  /** Trusted system instructions. */
  def systemText: String = sections._1

  /** User-turn template with `{{question}}`, `{{window}}`, `{{researchers}}`, `{{evidence}}`. */
  def userTemplate: String = sections._2

  /** Escape untrusted text so it cannot open or close a tag. */
  def escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private val Placeholder: Regex = """\{\{(\w+)\}\}""".r

  /** Single-pass substitution: substituted values are never re-scanned for placeholders. */
  private def fill(template: String, values: Map[String, String]): String =
    Placeholder.replaceAllIn(
      template,
      m =>
        Regex.quoteReplacement(
          values.getOrElse(m.group(1), throw IllegalStateException(s"no value for ${m.group(1)}"))
        )
    )

  def evidenceBlock(item: EvidenceItem): String =
    s"""<evidence id="${escape(item.evidenceId)}" type="${item.evidenceType}" """ +
      s"""source="${escape(item.sourceEntityId)}">\n${escape(item.content)}\n</evidence>"""

  def researcherLine(r: ResolvedResearcher): String =
    s"<researcher id=\"${escape(r.researcher.researcherId)}\" " +
      s"confidence=\"${r.confidence}\">${escape(r.researcher.displayName)}</researcher>"

  /** Build the LLM request for a query, its evidence and its resolved researchers. */
  def render(
    plan: QueryPlan,
    evidence: EvidenceBundle,
    researchers: Vector[ResolvedResearcher],
    maxTokens: Int
  ): LlmRequest =
    val window =
      s"${plan.filters.getOrElse("yearFrom", "unspecified")} to " +
        s"${plan.filters.getOrElse("yearTo", "unspecified")}"
    val user = fill(
      userTemplate,
      Map(
        "question"    -> escape(plan.originalQuery),
        "window"      -> escape(window),
        "researchers" -> researchers.map(researcherLine).mkString("\n"),
        "evidence"    -> evidence.items.map(evidenceBlock).mkString("\n")
      )
    )
    LlmRequest(systemText, user, maxTokens)

  /** Appended to the user turn on the single retry after invalid output. */
  def repairInstruction(errors: Vector[String]): String =
    "\nYour previous reply was rejected because it did not match the answer-synthesis-v2 " +
      s"schema (${errors.take(5).mkString("; ")}). Return only the JSON object."
