package com.keepit.model

case class Offset(value: Long) extends AnyVal
object Offset {
  val ZERO = Offset(0)
}
case class Limit(value: Long) extends AnyVal
