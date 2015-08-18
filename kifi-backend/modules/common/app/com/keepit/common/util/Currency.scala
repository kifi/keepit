package com.keepit.common.util

case class UnevenBillingRateException(cost: Currency, cycle: BillingCycle) extends Exception(s"$cost cannot be evenly divided into $cycle")
case class IllegalBillingCycleDivision(a: BillingCycle, b: BillingCycle) extends Exception(s"$a does not divide evenly by $b")

case class Currency(cents: Int) {
  def +(that: Currency): Currency = Currency(this.cents + that.cents)
  def -(that: Currency): Currency = Currency(this.cents - that.cents)
  def *(lambda: Int): Currency = Currency(this.cents * lambda)
  def /(lambda: Int): Currency = Currency(this.cents / lambda)
  def /(bc: BillingCycle): BillingRate = BillingRate(this, bc)
  override def toString = s"$$${cents / Currency.CENTS_PER_DOLLAR}.${cents % Currency.CENTS_PER_DOLLAR}"
}

object Currency {
  private val CENTS_PER_DOLLAR = 100
  def cents(n: Int) = Currency(n)
  def dollars(n: Int) = Currency(CENTS_PER_DOLLAR * n)
}

case class BillingCycle(months: Int) {
  def +(that: BillingCycle): BillingCycle = BillingCycle(this.months + that.months)
  def -(that: BillingCycle): BillingCycle = BillingCycle(this.months - that.months)
  def *(lambda: Int): BillingCycle = BillingCycle(this.months * lambda)
  def /(lambda: Int): BillingCycle = BillingCycle(this.months / lambda)
  def /(that: BillingCycle): Int = {
    if (this.months % that.months != 0) throw new IllegalBillingCycleDivision(this, that)
    else this.months / that.months
  }

  override def toString = {
    if (months > 1) s"$months months"
    else s"$months month"
  }
}
object BillingCycle {
  val MIN_LENGTH = BillingCycle.months(1)

  private val MONTHS_PER_YEAR = 12
  def months(n: Int) = BillingCycle(n)
  def years(n: Int) = BillingCycle(MONTHS_PER_YEAR * n)
}

case class BillingRate(cost: Currency) {
  def +(that: BillingRate): BillingRate = BillingRate(this.cost + that.cost)
  def -(that: BillingRate): BillingRate = BillingRate(this.cost - that.cost)
  override def toString = s"$cost / ${BillingRate.canonicalTimePeriod}"
}

object BillingRate {
  val canonicalTimePeriod = BillingCycle.months(1)
  def apply(cost: Currency, time: BillingCycle): BillingRate = {
    val lambda = time / canonicalTimePeriod
    if (cost.cents % lambda != 0) throw new UnevenBillingRateException(cost, time)
    else BillingRate(cost / lambda)
  }
}
