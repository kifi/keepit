package com.keepit.common.path

import com.keepit.common.net.Query
import com.keepit.common.strings._

import java.net.{ URLDecoder, URLEncoder }

import play.api.libs.json._

sealed class Path(private val value: String, private val query: Query) {
  def encode: EncodedPath = new EncodedPath(value, query)
  def decode: Path = this

  def isEncoded: Boolean = false

  def absolute: String = {
    if (relative.startsWith("/")) {
      Path.base + relative.drop(1) + (if (query != Query.empty) s"?$query" else "")
    } else {
      Path.base + relative + (if (query != Query.empty) s"?$query" else "")
    }
  }

  def relative: String = value + (if (query != Query.empty) s"?$query" else "")

  override def toString: String = value

  def +(segment: String) = Path(value + segment)

  def withQuery(query: Query, overwrite: Boolean = false) = new Path(value, if (overwrite) query else this.query ++ query)
}

class EncodedPath(private val value: String, private val query: Query) extends Path(URLEncoder.encode(value, UTF8), query) {

  override def encode: EncodedPath = this

  override def decode: Path = new Path(value, query)

  override def isEncoded: Boolean = true

}

object Path {

  def base: String = "https://www.kifi.com/"

  def apply(value: String): Path = new Path(value, Query.empty)

  def encode(value: String): EncodedPath = new EncodedPath(value, Query.empty)

  def alreadyEncoded(value: String): EncodedPath = new EncodedPath(URLDecoder.decode(value, UTF8), Query.empty)

  implicit val format: Format[Path] = Format(
    __.read[String].map(str => Path(str.substring(base.length))),
    Writes(path => JsString(path.absolute))
  )

  implicit val encodedFormat: Format[EncodedPath] = Format(
    __.read[String].map(str => alreadyEncoded(str.substring(base.length))),
    Writes(path => JsString(path.absolute))
  )

}
