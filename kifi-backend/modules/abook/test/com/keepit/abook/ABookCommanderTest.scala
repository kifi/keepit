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

class ABookCommanderTest extends Specification with DbTestInjector with ABookTestHelper {

  def setup()(implicit injector:Injector) = {
    val db = inject[Database]
    val airbrake = inject[AirbrakeNotifier]
    val abookInfoRepo = inject[ABookInfoRepo]
    val contactRepo = inject[ContactRepo]
    val econtactRepo = inject[EContactRepo]
    val contactsUpdater = inject[ContactsUpdaterPlugin]
    val s3 = inject[ABookRawInfoStore]
    val commander = new ABookCommander(db, airbrake, s3, abookInfoRepo, contactRepo, econtactRepo, contactsUpdater)
    commander
  }

  implicit def strSeqToJsArray(s:Seq[String]):JsArray = JsArray(s.map(JsString(_)))

//  implicit val system = ActorSystem("test")
  val modules = Seq(
    FakeABookRawInfoStoreModule(),
    TestContactsUpdaterPluginModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    FakeClockModule(),
//    StandaloneTestActorSystemModule(),
    FakeAirbrakeModule(),
    ABookCacheModule(HashMapMemoryCacheModule())
  )

  "ABook Commander" should {

    "handle imports from IOS and gmail" in {
      withDb(modules: _*) { implicit injector =>
        val (commander) = setup()

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

        val contactsJsArr = commander.getContactsDirect(u42, 500)
        val contactsSeqOpt = contactsJsArr.validate[Seq[Contact]].asOpt
        val contactsSeq = contactsSeqOpt.get
        contactsSeq.isEmpty === false
        contactsSeq.length === 3

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

        val e2 = "foo@42go.com"
        val e2Res = commander.getOrCreateEContact(u42, e2)
        e2Res.isSuccess === true

        val npeRes = commander.getOrCreateEContact(u42, null)
        npeRes.isSuccess === false

        val e1 = "foobar@42go.com"
        val e1Res = commander.getOrCreateEContact(u42, e1)
        e1Res.isSuccess === true
      }
    }

    "handle imports from multiple gmail accounts" in {
      withDb(modules: _*) { implicit injector =>
        val (commander) = setup()
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
  }
}

