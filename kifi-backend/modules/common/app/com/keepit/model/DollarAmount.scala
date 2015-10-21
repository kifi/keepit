package com.keepit.model

import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._

@json
case class DollarAmount(cents: Int) extends Ordered[DollarAmount] {
  def compare(that: DollarAmount) = cents compare that.cents
  def +(other: DollarAmount): DollarAmount = DollarAmount(cents + other.cents)
  def -(other: DollarAmount): DollarAmount = DollarAmount(cents - other.cents)
  def *(x: Int) = DollarAmount(cents * x)
  def max(other: DollarAmount): DollarAmount = DollarAmount(cents max other.cents)
  def min(other: DollarAmount): DollarAmount = DollarAmount(cents min other.cents)
  override def toString = toDollarString
  def toDollarString: String = if (cents < 0) "-" + (-this).toDollarString else "$%d.%02d".format(cents / 100, cents % 100)

  def unary_- = DollarAmount(-1 * cents)

  def toCents: Int = cents
}

object DollarAmount {
  def cents(cents: Int): DollarAmount = DollarAmount(cents)
  def dollars(dollars: Int): DollarAmount = DollarAmount(dollars * 100)

  val ZERO = DollarAmount(0)

  val formatAsCents: Format[DollarAmount] = (__ \ 'cents).format[Int].inmap(DollarAmount.cents, _.toCents)
}
