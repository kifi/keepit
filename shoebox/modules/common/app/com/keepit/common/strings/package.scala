package com.keepit.common

package object strings {
  val UTF8 = "UTF-8"
  implicit def fromByteArray(bytes: Array[Byte]): String = new String(bytes, UTF8)
  implicit def toByteArray(str: String): Array[Byte] = str.getBytes(UTF8)
}
