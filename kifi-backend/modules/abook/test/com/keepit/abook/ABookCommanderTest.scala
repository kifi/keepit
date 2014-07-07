package com.keepit.abook

import org.specs2.mutable._
import com.keepit.test.DbTestInjector
import com.google.inject.Injector
import com.keepit.common.db.slick.Database
import com.keepit.abook.store.ABookRawInfoStore
import com.keepit.model._
import com.keepit.common.db.{TestDbInfo, Id}
import play.api.libs.json._
import play.api.libs.json.JsArray
import com.keepit.common.time.FakeClockModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.ABookCacheModule
import play.api.libs.json.JsString
import scala.Some
import com.keepit.common.db.TestSlickModule
import com.keepit.common.healthcheck.{AirbrakeNotifier, FakeAirbrakeModule}
import com.keepit.typeahead.abook.{EContactTypeaheadStore, EContactTypeahead}
import com.keepit.abook.typeahead.EContactABookTypeahead
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.mail.{EmailAddress, BasicContact}

class ABookCommanderTest extends Specification with DbTestInjector with ABookTestHelper {

  implicit def strSeqToJsArray(s:Seq[String]):JsArray = JsArray(s.map(JsString(_)))

  val modules = Seq(
    FakeABookStoreModule(),
    TestContactsUpdaterPluginModule(),
    TestABookServiceClientModule(),
    FakeShoeboxServiceModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    FakeClockModule(),
    FakeAirbrakeModule(),
    ABookCacheModule(HashMapMemoryCacheModule()),
    FakeAbookRepoChangeListenerModule()
  )

  "ABook Commander" should {

    "handle imports from IOS and gmail" in {
      withDb(modules: _*) { implicit injector =>
        val (commander) = inject[ABookCommander] //setup()

      db.readOnly(inject[ABookInfoRepo].count(_))
        // empty abook upload
        val emptyABookRawInfo = ABookRawInfo(None, ABookOrigins.IOS, None, None, None, JsArray(Seq.empty))
        val emptyABookOpt = commander.processUpload(u42, ABookOrigins.IOS, None, None, Json.toJson(emptyABookRawInfo))
        emptyABookOpt.isEmpty === true

        var abookInfo:ABookInfo = try {
          val info1 = commander.processUpload(u42, ABookOrigins.IOS, None, None, iosUploadJson).get
//          val info2 = commander.processUpload(u42, ABookOrigins.IOS, None, None, iosUploadJson) // should have no impact
          info1.state !== ABookInfoStates.UPLOAD_FAILURE
//          info2.state !== ABookInfoStates.UPLOAD_FAILURE
          info1
        } catch {
          case e:Exception => {
            e.printStackTrace(System.out)
            throw e
          }
        }
        abookInfo.id.get === Id[ABookInfo](1)
        abookInfo.origin === ABookOrigins.IOS
        abookInfo.userId === u42
        var nWait = 0
        while (abookInfo.state != ABookInfoStates.ACTIVE && nWait < 10) {
          nWait += 1
          abookInfo = commander.getABookInfo(u42, abookInfo.id.get).get
          Thread.sleep(1000)
        }
        abookInfo.state === ABookInfoStates.ACTIVE

        val abookInfos = commander.getABookRawInfosDirect(u42)
        val abookInfoSeqOpt = abookInfos.validate[Seq[ABookRawInfo]].asOpt
        abookInfoSeqOpt.isEmpty === false
        val aBookRawInfoSeq = abookInfoSeqOpt.get
        aBookRawInfoSeq.length === 1
        val contacts = aBookRawInfoSeq(0).contacts.value
        contacts.length === 3
        (contacts(0) \ "name").as[String] === "foo bar"
        (contacts(0) \ "emails").as[Seq[String]].length === 2
        (contacts(1) \ "name").as[String] === "forty two"
        (contacts(1) \ "emails").as[Seq[String]].length === 3

        val gbookInfo:ABookInfo = commander.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner), None, gmailUploadJson).get
        gbookInfo.id.get === Id[ABookInfo](2)
        gbookInfo.origin === ABookOrigins.GMAIL
        gbookInfo.userId === u42

        val gbookInfos = commander.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt = gbookInfos.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt.isEmpty === false
        val gBookRawInfoSeq = gbookInfoSeqOpt.get
        gBookRawInfoSeq.length === 2

        val econtactsJsArr = commander.getEContactsDirect(u42, 500)
        val econtactsSeqOpt = econtactsJsArr.validate[Seq[EContact]].asOpt
        econtactsSeqOpt.isEmpty === false
        val econtactsSeq = econtactsSeqOpt.get
        econtactsSeq.isEmpty === false
        econtactsSeq.length === 4 // distinct

        // todo: remove queryEContacts
        var qRes = commander.queryEContacts(u42, 10, None, None)
        qRes.isEmpty !== true
        qRes.length === 4
        qRes = commander.queryEContacts(u42, 10, Some("ray"), None)
        qRes.isEmpty !== true
        qRes.length === 1
        qRes = commander.queryEContacts(u42, 10, Some("foo"), None)  // name and email both considered in our current alg
        qRes.isEmpty !== true
        qRes.length === 2

        val e2 = BasicContact.fromString("foo@42go.com").get
        val e2Res = commander.internContact(u42, e2)
        e2Res.email.address === "foo@42go.com"
        e2Res.name must beSome("foo bar")
        e2Res.firstName must beSome("foo")
        e2Res.lastName must beSome("bar")

        val e1 = BasicContact.fromString("foobar@42go.com").get
        val e1Res = commander.internContact(u42, e1)
        e1Res.email.address === "foobar@42go.com"
        e1Res.name must beNone

        val e3 = BasicContact.fromString("Douglas Adams <doug@kifi.com>").get
        val e3Res = commander.internContact(u42, e3)
        e3Res.email.address === "doug@kifi.com"
        e3Res.name must beSome("Douglas Adams")

        val e4 = BasicContact.fromString("Marvin Adams <marvin@kifi.com>").get.copy(name = Some("Smada Nivram"))
        val e4Res = commander.internContact(u42, e4)
        e4Res.email.address === "marvin@kifi.com"
        e4Res.name must beSome("Smada Nivram")
      }
    }

    "handle imports from multiple gmail accounts" in {
      withDb(modules: _*) { implicit injector =>
        val (commander) = inject[ABookCommander] // setup()
        val gbookInfo:ABookInfo = commander.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner), None, gmailUploadJson).get
        gbookInfo.id.get === Id[ABookInfo](1)
        gbookInfo.origin === ABookOrigins.GMAIL
        gbookInfo.userId === u42

        val gbookInfos = commander.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt = gbookInfos.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt.isEmpty === false
        val gBookRawInfoSeq = gbookInfoSeqOpt.get
        gBookRawInfoSeq.length === 1

        val gbookInfo2:ABookInfo = commander.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner2), None, gmailUploadJson2).get
        gbookInfo2.id.get === Id[ABookInfo](2)
        gbookInfo2.origin === ABookOrigins.GMAIL
        gbookInfo2.userId === u42

        val gbookInfos2 = commander.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt2 = gbookInfos2.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt2.isEmpty === false
        val gBookRawInfoSeq2 = gbookInfoSeqOpt2.get
        gBookRawInfoSeq2.length === 2
      }
    }

    "handle hiding given email from current user" in  {
      withDb(modules: _*) { implicit injector =>
        val (commander) = inject[ABookCommander]
        val (econRepo) = inject[EContactRepo] // setup()

        val e1 = BasicContact.fromString("Douglas Adams <doug@kifi.com>").get
        val e1Res = commander.internContact(u42, e1)

        val result1 = commander.hideEmailFromUser(u42, e1Res.email)
        result1 > 0

        db.readOnly { implicit session =>
            val e2 = econRepo.get(e1Res.id.get)
            e2.state === EContactStates.HIDDEN
        }

        val result2 = commander.hideEmailFromUser(u42, EmailAddress("nonexist@email.com"))
        result2 === 0

      }
    }

  }
}

