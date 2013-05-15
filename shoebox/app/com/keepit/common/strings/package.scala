package com.keepit.common

package object strings {
  val ENCODING = "UTF-8"
  implicit def fromByteArray(bytes: Array[Byte]): String = new String(bytes, ENCODING)
  implicit def toByteArray(str: String): Array[Byte] = str.getBytes(ENCODING)
}
