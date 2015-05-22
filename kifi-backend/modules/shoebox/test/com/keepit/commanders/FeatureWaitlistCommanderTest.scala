package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.social.FakeSocialGraphModule
import org.specs2.mutable.Specification

import com.keepit.test.{ ShoeboxTestInjector, DbInjectionHelper }
import com.keepit.common.mail.{ FakeMailModule, FakeOutbox }
import com.keepit.model.FeatureWaitlistRepo
import com.keepit.common.db.slick.Database

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FeatureWaitlistCommanderTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  val modules = Seq(
    FakeMailModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule())

  "FeatureWaitlistCommander" should {
    "wait list correctly" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[FeatureWaitlistCommander]
        val repo = inject[FeatureWaitlistRepo]
        val outbox = inject[FakeOutbox]
        val db = inject[Database]

        outbox.size === 0

        val extId = Await.result(commander.waitList("stephen@42go.com", "mobile_app", "Test Browser/0.42"), Duration(5, "seconds"))

        outbox.size === 1
        outbox(0).to.length === 1
        outbox(0).to(0).address === "stephen@42go.com"
        val data1 = db.readOnlyMaster { implicit session => repo.all() }
        data1.length === 1
        val datum1 = data1(0)
        datum1.email === "stephen@42go.com"
        datum1.feature === "mobile_app"
        datum1.userAgent === "Test Browser/0.42"

        Await.ready(commander.waitList("stephen+waitlist@42go.com", "mobile_app", "Test Browser/0.42", Some(extId)), Duration(5, "seconds"))
        outbox.size === 2
        val data2 = db.readOnlyMaster { implicit session => repo.all() }
        data2.length === 1
        val datum2 = data2(0)
        datum2.email === "stephen+waitlist@42go.com"
        datum2.feature === "mobile_app"
        datum2.userAgent === "Test Browser/0.42"

        Await.ready(commander.waitList("stephen+other@42go.com", "lynx_support", "curl"), Duration(5, "seconds"))
        outbox.size === 2
        val data3 = db.readOnlyMaster { implicit session => repo.all() }
        data3.length === 2
        val datum3 = data3(1)
        datum3.email === "stephen+other@42go.com"
        datum3.feature === "lynx_support"
        datum3.userAgent === "curl"

      }
    }
  }

}
