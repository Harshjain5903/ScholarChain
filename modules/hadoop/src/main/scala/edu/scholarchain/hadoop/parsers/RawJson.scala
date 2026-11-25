package edu.scholarchain.hadoop.parsers

import ujson.Value

/** Small null-safe accessors shared by the raw-shape adapters of the source parsers. */
private[parsers] object RawJson:

  /** The string at `key`, when present, non-null and a string. */
  def str(obj: collection.Map[String, Value], key: String): Option[String] =
    obj.get(key).collect { case ujson.Str(s) => s }

  /** The object at `key`, when present and an object. */
  def obj(obj: collection.Map[String, Value], key: String): Option[collection.Map[String, Value]] =
    obj.get(key).collect { case o: ujson.Obj => o.value }

  /** The array elements at `key`, empty when absent, null or not an array. */
  def arr(obj: collection.Map[String, Value], key: String): Vector[Value] =
    obj.get(key).collect { case a: ujson.Arr => a.value.toVector }.getOrElse(Vector.empty)

  /** First non-blank string of the array at `key`. */
  def firstStr(obj: collection.Map[String, Value], key: String): Option[String] =
    arr(obj, key).collectFirst { case ujson.Str(s) if s.trim.nonEmpty => s }

  /** `date-parts[0][0]` of the Crossref date object at `key` as a year. */
  def datePartsYear(obj: collection.Map[String, Value], key: String): Option[Int] =
    RawJson
      .obj(obj, key)
      .flatMap(d => arr(d, "date-parts").headOption)
      .collect { case a: ujson.Arr => a.value.headOption }
      .flatten
      .collect { case ujson.Num(n) => n.toInt }
