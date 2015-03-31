package com.keepit.common.time

import com.google.inject.Singleton
import com.keepit.common.logging.Logging
import scala.collection.mutable
import org.joda.time.{ ReadablePeriod, DateTime }
import net.codingwell.scalaguice.ScalaModule

case class FakeClockModule() extends ScalaModule {
  def configure() {
    bind[Clock].to[FakeClock]
  }
}

/**
 * A fake clock allows you to control the time returned by Clocks in tests.
 *
 * If you know how many times the underlying code will call getMillis(), you can push() times onto the stack to have
 * their values returned. You can also completely override the time function by calling setTimeFunction().
 */

@Singleton
class FakeClock extends Clock with Logging {
  private val stack = mutable.Stack[Long]()
  private var timeFunction: () => Long = () => {
    if (stack.isEmpty) {
      val nowTime = new DateTime(System.currentTimeMillis(), DEFAULT_DATE_TIME_ZONE)
      log.debug(s"FakeClock is retuning real now value: $nowTime")
      nowTime.getMillis
    } else {
      val fakeNowTime = new DateTime(stack.pop(), DEFAULT_DATE_TIME_ZONE)
      log.debug(s"FakeClock is retuning fake now value: $fakeNowTime")
      fakeNowTime.getMillis
    }
  }

  def +=(p: ReadablePeriod) {
    val oldTimeFunction = timeFunction
    timeFunction = { () => new DateTime(oldTimeFunction(), DEFAULT_DATE_TIME_ZONE).plus(p).getMillis }
  }

  def -=(p: ReadablePeriod) {
    val oldTimeFunction = timeFunction
    timeFunction = { () => new DateTime(oldTimeFunction(), DEFAULT_DATE_TIME_ZONE).minus(p).getMillis }
  }

  def push(t: DateTime): FakeClock = { stack push t.getMillis; this }
  def setTimeFunction(timeFunction: () => DateTime) { this.timeFunction = { timeFunction().getMillis } }
  def setTimeValue(time: DateTime) { this.timeFunction = () => time.getMillis }
  override def getMillis(): Long = timeFunction()
}

