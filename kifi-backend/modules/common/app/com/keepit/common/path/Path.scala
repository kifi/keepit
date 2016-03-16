package com.keepit.common.path

import com.keepit.common.net.{ Param, Query }
import com.keepit.common.strings._

import java.net.{ URLDecoder, URLEncoder }

import play.api.libs.json._

sealed class Path(private val value: String, private val query: Query) {
  def encode: EncodedPath = new EncodedPath(value, query)
  def decode: Path = this

  def isEncoded: Boolean = false

  def absolute: String = Path.base + relative.stripPrefix("/") + query.toUrlString

  def relative: String = value
  def queryString: String = query.toUrlString

  override def toString: String = value

  def +(segment: String) = {
    val (segmentValue, segmentQuery) = {
      if (segment.startsWith("&")) Path.splitQuery(segment.replaceFirst("&", "?"))
      else Path.splitQuery(segment)
    }
    new Path(value + "/" + segmentValue.stripPrefix("/"), query ++ segmentQuery)
  }

  def withQuery(query: Query, overwrite: Boolean = false): Path = new Path(value, if (overwrite) query else this.query ++ query)
  def withQueryString(str: String, overwrite: Boolean = false): Path = withQuery(Query.parse(str), overwrite)
}

class EncodedPath(private val value: String, private val query: Query) extends Path(URLEncoder.encode(value, UTF8), Query.parse(URLEncoder.encode(query.toString, UTF8))) {

  override def encode: EncodedPath = this

  override def decode: Path = new Path(value, query)

  override def isEncoded: Boolean = true

}

object Path {

  def base: String = "https://www.kifi.com/"

  private def splitQuery(str: String): (String, Query) = str.split('?').toList match {
    case relative :: params => (relative, params.map(Query.parse).foldLeft(Query.empty)(_ ++ _))
  }

  def apply(value: String): Path = {
    val (path, query) = splitQuery(value)
    new Path(path, query)
  }

  def encode(value: String): EncodedPath = {
    val (path, query) = splitQuery(value)
    new EncodedPath(path, query)
  }

  def alreadyEncoded(value: String): EncodedPath = {
    val (path, query) = splitQuery(URLDecoder.decode(value, UTF8))
    new EncodedPath(path, query)
  }

  implicit val format: Format[Path] = Format(
    __.read[String].map(str => Path(str.substring(base.length))),
    Writes(path => JsString(path.absolute))
  )

  implicit val encodedFormat: Format[EncodedPath] = Format(
    __.read[String].map(str => alreadyEncoded(str.substring(base.length))),
    Writes(path => JsString(path.absolute))
  )

}
