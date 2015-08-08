package com.keepit.common.path

import com.keepit.common.strings._

import java.net.URLEncoder

sealed class Path(private val value: String) {

  def encode: Path = new EncodedPath(value)
  def decode: Path = this

  def isEncoded: Boolean = false

  def absolute: String = Path.base + relative

  def relative: String = value

  override def toString: String = value

}

class EncodedPath(private val value: String) extends Path(URLEncoder.encode(value, UTF8)) {

  override def encode: Path = this

  override def decode: Path = new Path(value)

  override def isEncoded: Boolean = true

}

object Path {

  def base: String = "https://www.kifi.com/"

  def apply(value: String): Path = new Path(value)

  def encode(value: String): Path = new EncodedPath(value)

}
