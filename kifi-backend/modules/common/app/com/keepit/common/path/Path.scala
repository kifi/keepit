package com.keepit.common.path

import com.keepit.common.net.Query
import com.keepit.common.strings._

import java.net.{ URLDecoder, URLEncoder }

import play.api.libs.json._

sealed abstract class AbstractPath(private val value: String, private val query: Query) {
  def encode: EncodedPath
  def decode: Path
  def isEncoded: Boolean

  def queryString: String = query.toUrlString
  def relativeWithLeadingSlash: String = "/" + value.stripPrefix("/") + queryString
  def absolute: String = Path.base + relativeWithLeadingSlash

  override def toString: String = relativeWithLeadingSlash

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

case class Path(private val value: String, private val query: Query) extends AbstractPath(value, query) {
  def encode: EncodedPath = new EncodedPath(value, query)
  def decode: Path = this
  def isEncoded: Boolean = false
}

case class EncodedPath(private val value: String, private val query: Query) extends AbstractPath(URLEncoder.encode(value, UTF8), Query.parse(URLEncoder.encode(query.toString, UTF8))) {
  override def encode: EncodedPath = this
  override def decode: Path = new Path(value, query)
  override def isEncoded: Boolean = true
}

object Path {

  def base: String = "https://www.kifi.com"

  def splitQuery(str: String): (String, Query) = str.split('?').toList match {
    case relative :: params => (relative, params.map(Query.parse).foldLeft(Query.empty)(_ ++ _))
    case _ => ("", Query.empty)
  }

  def apply(value: String): Path = {
    val (path, query) = splitQuery(value)
    new Path(path.stripPrefix(base).stripPrefix("/"), query)
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
    __.read[String].map(str => Path(str)),
    Writes(path => JsString(path.relativeWithLeadingSlash))
  )

  implicit val encodedFormat: Format[EncodedPath] = Format(
    __.read[String].map(str => alreadyEncoded(str.substring(base.length))),
    Writes(path => JsString(path.absolute))
  )

}
