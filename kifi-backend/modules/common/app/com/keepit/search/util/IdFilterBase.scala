package com.keepit.search.util

import javax.xml.bind.DatatypeConverter._

import com.keepit.serializer.ArrayBinaryFormat

trait IdFilterBase[T <: Set[Long]] {

  protected val emptySet: T

  protected def toByteArray(ids: Set[Long]): Array[Byte]

  protected def toSet(bytes: Array[Byte]): T

  def fromSetToBase64(ids: Set[Long]): String = printBase64Binary(toByteArray(ids))

  def fromBase64ToSet(base64: String): T = {
    if (base64.length == 0) emptySet
    else {
      val bytes = try {
        parseBase64Binary(base64)
      } catch {
        case e: Exception => throw new IdFilterCompressorException("failed to decode base64", e)
      }
      toSet(bytes)
    }
  }

}

class IdFilterCompressorException(msg: String, exception: Exception) extends Exception(msg) {
  def this(msg: String) = this(msg, null)
}

class LongSetIdFilter extends IdFilterBase[Set[Long]] {
  protected val emptySet: Set[Long] = Set()

  private val formatter = ArrayBinaryFormat.longArrayFormat

  protected def toByteArray(ids: Set[Long]): Array[Byte] = {
    formatter.writes(Some(ids.toArray))
  }

  protected def toSet(bytes: Array[Byte]): Set[Long] = {
    formatter.reads(bytes) match {
      case Some(arr) => arr.toSet
      case None => emptySet
    }
  }
}
