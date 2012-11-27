package com.keepit.test

import play.api.GlobalSettings
import play.api.Application
import com.google.inject.Module
import com.google.inject.Singleton
import com.google.inject.util.Modules
import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.shoebox.ShoeboxModule
import com.keepit.dev.DevModule
import com.keepit.inject._
import com.keepit.FortyTwoGlobal
import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.store.FakeStoreModule
import org.joda.time.DateTime
import scala.collection.mutable.{Stack => MutableStack}
import com.google.inject.Provides
import org.joda.time.LocalDate

class TestApplication(override val global: TestGlobal) extends play.api.test.FakeApplication() {
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeStoreModule())
  def withFakeClock() = overrideWith(FakeClockModule())

  def overrideWith(model: Module): TestApplication =
    new TestApplication(new TestGlobal(Modules.`override`(global.module).`with`(model)))
}

class DevApplication() extends TestApplication(new TestGlobal(DevModule()))
class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxModule()))
class EmptyApplication() extends TestApplication(new TestGlobal(FortyTwoModule()))

case class FakeClockModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides @Singleton
  def clock = new FakeClock

  @Provides
  def dateTime(clock: FakeClock) : DateTime = clock.pop

  @Provides
  def localDate(clock: FakeClock) : LocalDate = clock.pop.toLocalDate
}

class FakeClock {
  val stack = MutableStack[DateTime]()

  def push(t : DateTime): FakeClock = { stack push t; this }
  def push(d : LocalDate): FakeClock = { stack push d.toDateTimeAtStartOfDay(DEFAULT_DATE_TIME_ZONE); this }
  def pop(): DateTime = if (stack.isEmpty) currentDateTime else stack.pop
}
