package com.keepit.common

import org.apache.commons.lang3.StringUtils

package object strings {
  val UTF8 = "UTF-8"
  implicit def fromByteArray(bytes: Array[Byte]): String = if (bytes == null) "" else new String(bytes, UTF8)
  implicit def toByteArray(str: String): Array[Byte] = str.getBytes(UTF8)
  def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): String = if (bytes == null) "" else new String(bytes, offset, length, UTF8)

  implicit class AbbreviateString(str: String) {
    def abbreviate(count: Int) = StringUtils.abbreviate(str, count)
  }

}
