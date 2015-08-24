package com.keepit.common.path

import com.keepit.common.strings._

import java.net.{ URLDecoder, URLEncoder }

import play.api.libs.json._

sealed class Path(private val value: String) {

  def encode: EncodedPath = new EncodedPath(value)
  def decode: Path = this

  def isEncoded: Boolean = false

  def absolute: String = Path.base + relative

  def relative: String = value

  override def toString: String = value

}

class EncodedPath(private val value: String) extends Path(URLEncoder.encode(value, UTF8)) {

  override def encode: EncodedPath = this

  override def decode: Path = new Path(value)

  override def isEncoded: Boolean = true

}

object Path {

  def base: String = "https://www.kifi.com/"

  def apply(value: String): Path = new Path(value)

  def encode(value: String): EncodedPath = new EncodedPath(value)

  def alreadyEncoded(value: String): EncodedPath = new EncodedPath(URLDecoder.decode(value, UTF8))

  implicit val format: Format[Path] = Format(
    __.read[String].map(str => alreadyEncoded(str.substring(base.length))),
    Writes(path => JsString(path.encode.absolute))
  )

  implicit val encodedFormat: Format[EncodedPath] = Format(
    __.read[String].map(str => alreadyEncoded(str.substring(base.length))),
    Writes(path => JsString(path.absolute))
  )

}
