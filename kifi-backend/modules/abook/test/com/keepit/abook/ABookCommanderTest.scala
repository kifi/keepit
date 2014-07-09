package com.keepit.abook

import org.specs2.mutable._
import com.keepit.test.DbTestInjector
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
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.mail.{EmailAddress, BasicContact}
import com.keepit.abook.model.{EContactStates, EContactRepo}

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

    "handle imports from IOS and gmail and interning of Kifi contacts independently" in {
      withDb(modules: _*) { implicit injector =>
        val (commander) = inject[ABookCommander] //setup()

        // EMPTY IOS IMPORT
      db.readOnlyMaster(inject[ABookInfoRepo].count(_))
        val emptyABookRawInfo = ABookRawInfo(None, ABookOrigins.IOS, None, None, None, JsArray(Seq.empty))
        val emptyABookOpt = commander.processUpload(u42, ABookOrigins.IOS, None, None, Json.toJson(emptyABookRawInfo))
        emptyABookOpt.isEmpty === true

        // NON EMPTY IOS IMPORT
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

        // GMAIL IMPORT

        val gbookInfo:ABookInfo = commander.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner), None, gmailUploadJson).get
        gbookInfo.id.get === Id[ABookInfo](2)
        gbookInfo.origin === ABookOrigins.GMAIL
        gbookInfo.userId === u42

        val gbookInfos = commander.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt = gbookInfos.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt.isEmpty === false
        val gBookRawInfoSeq = gbookInfoSeqOpt.get
        gBookRawInfoSeq.length === 2

        val eContacts = commander.getContactsByUser(u42)
        eContacts.isEmpty === false
        eContacts.length === 8

        // INTERN KIFI CONTACTS

        val e1 = BasicContact.fromString("foobar@42go.com").get
        val e1Res = commander.internKifiContact(u42, e1)
        e1Res.email.address === "foobar@42go.com"
        e1Res.name must beNone

        val e2 = BasicContact.fromString("Douglas Adams <doug@kifi.com>").get
        val e2Res = commander.internKifiContact(u42, e2)
        e2Res.email.address === "doug@kifi.com"
        e2Res.name must beSome("Douglas Adams")

        val e3 = BasicContact.fromString("Marvin Adams <marvin@kifi.com>").get.copy(name = Some("Smada Nivram"))
        val e3Res = commander.internKifiContact(u42, e3)
        e3Res.email.address === "marvin@kifi.com"
        e3Res.name must beSome("Smada Nivram")
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
        val e1Res = commander.internKifiContact(u42, e1)

        val result1 = commander.hideEmailFromUser(u42, e1Res.email)
        result1 === true

        db.readOnlyMaster { implicit session =>
            val e2 = econRepo.get(e1Res.id.get)
            e2.state should_== EContactStates.HIDDEN
        }

        val result2 = commander.hideEmailFromUser(u42, EmailAddress("nonexist@email.com"))
        result2 === false

      }
    }

  }
}

