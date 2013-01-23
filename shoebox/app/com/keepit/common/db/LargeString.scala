package com.keepit.common.db

case class LargeString(value: String) {
  override def toString = value
}

object LargeString {
  implicit def toStandardString(value: LargeString): String = value.value
  implicit def toLargeString(value: String): LargeString = LargeString(value)
}
