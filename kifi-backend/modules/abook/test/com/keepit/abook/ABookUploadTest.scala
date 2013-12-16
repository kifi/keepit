package com.keepit.abook

import org.specs2.mutable._
import com.keepit.test.DbTestInjector
import com.google.inject.Injector
import com.keepit.common.db.slick.Database
import com.keepit.abook.store.ABookRawInfoStore
import com.keepit.model._
import com.keepit.common.db.{TestDbInfo, Id}
import play.api.libs.json._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import play.api.libs.json.JsArray
import com.keepit.common.time.FakeClockModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.ABookCacheModule
import play.api.libs.json.JsString
import scala.Some
import com.keepit.common.db.TestSlickModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import akka.actor.ActorSystem

class ABookUploadTest extends Specification with DbTestInjector {

  def setup()(implicit injector:Injector) = {
    val db = inject[Database]
    val abookInfoRepo = inject[ABookInfoRepo]
    val contactRepo = inject[ContactRepo]
    val econtactRepo = inject[EContactRepo]
    val contactsUpdater = inject[ContactsUpdaterPlugin]
    val s3 = inject[ABookRawInfoStore]
    val commander = new ABookCommander(db, s3, abookInfoRepo, contactRepo, econtactRepo, contactsUpdater)
    commander
  }

  implicit def strSeqToJsArray(s:Seq[String]):JsArray = JsArray(s.map(JsString(_)))

  val u42 = Id[User](42)

  val c53 = Json.arr(
    Json.obj(
      "name" -> "fifty three",
      "firstName" -> "fifty",
      "lastName" -> "three",
      "emails" -> Seq("fiftythree@53go.com"))
  )

  val c42 = Json.arr(
    Json.obj(
      "name" -> "foo bar",
      "firstName" -> "foo",
      "lastName" -> "bar",
      "emails" -> Seq("foo@42go.com", "bar@42go.com")),
    Json.obj(
      "name" -> "forty two",
      "firstName" -> "forty",
      "lastName" -> "two",
      "emails" -> Seq("fortytwo@42go.com", "Foo@42go.com ", "BAR@42go.com  ")),
    Json.obj(
      "name" -> "ray",
      "firstName" -> "ray",
      "lastName" -> "ng",
      "emails" -> Seq("ray@42go.com", " rAy@42GO.COM "))
    )

  "ABook Controller" should {

    "handle imports from IOS and gmail" in {
      implicit val system = ActorSystem("test")
      withDb(
        FakeABookRawInfoStoreModule(),
        TestSlickModule(TestDbInfo.dbInfo),
        FakeClockModule(),
        StandaloneTestActorSystemModule(),
        FakeAirbrakeModule(),
        ABookCacheModule(HashMapMemoryCacheModule())) { implicit injector =>
        val (commander) = setup()
        val iosUploadJson = Json.obj(
          "origin" -> "ios",
          "contacts" -> c42
        )

        var abookInfo:ABookInfo = try {
          val info1 = commander.processUpload(u42, ABookOrigins.IOS, None, None, iosUploadJson)
          val info2 = commander.processUpload(u42, ABookOrigins.IOS, None, None, iosUploadJson) // should have no impact
          info1.state mustNotEqual ABookInfoStates.UPLOAD_FAILURE
          info2.state mustNotEqual ABookInfoStates.UPLOAD_FAILURE
          info1
        } catch {
          case e:Exception => {
            e.printStackTrace(System.out)
            throw e
          }
        }
        abookInfo.id.get mustEqual Id[ABookInfo](1)
        abookInfo.origin mustEqual ABookOrigins.IOS
        abookInfo.userId mustEqual u42
        var nWait = 0
        while (abookInfo.state != ABookInfoStates.ACTIVE && nWait < 10) {
          nWait += 1
          abookInfo = commander.getABookInfo(u42, abookInfo.id.get).get
          Thread.sleep(1000)
        }
        abookInfo.state mustEqual ABookInfoStates.ACTIVE

        val abookInfos = commander.getABookRawInfosDirect(u42)
        val abookInfoSeqOpt = abookInfos.validate[Seq[ABookRawInfo]].asOpt
        abookInfoSeqOpt.isEmpty mustEqual false
        val aBookRawInfoSeq = abookInfoSeqOpt.get
        aBookRawInfoSeq.length mustEqual 1
        val contacts = aBookRawInfoSeq(0).contacts.value
        contacts.length mustEqual 3
        (contacts(0) \ "name").as[String] mustEqual "foo bar"
        (contacts(0) \ "emails").as[Seq[String]].length mustEqual 2
        (contacts(1) \ "name").as[String] mustEqual "forty two"
        (contacts(1) \ "emails").as[Seq[String]].length mustEqual 3

        val contactsJsArr = commander.getContactsDirect(u42, 500)
        val contactsSeqOpt = contactsJsArr.validate[Seq[Contact]].asOpt
        val contactsSeq = contactsSeqOpt.get
        contactsSeq.isEmpty mustEqual false
        contactsSeq.length mustEqual 3

        val gmailOwner = GmailABookOwnerInfo(Some("123456789"), Some("42@42go.com"), Some(true), Some("42go.com"))
        val gmailUploadJson = Json.obj(
          "origin"      -> "gmail",
          "ownerId"     -> gmailOwner.id.get,
          "ownerEmail"  -> gmailOwner.email.get,
          "contacts"    -> c42
        )
        val gbookInfo:ABookInfo = commander.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner), None, gmailUploadJson)
        gbookInfo.id.get mustEqual Id[ABookInfo](2)
        gbookInfo.origin mustEqual ABookOrigins.GMAIL
        gbookInfo.userId mustEqual u42

        val gbookInfos = commander.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt = gbookInfos.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt.isEmpty mustEqual false
        val gBookRawInfoSeq = gbookInfoSeqOpt.get
        gBookRawInfoSeq.length mustEqual 2

        val econtactsJsArr = commander.getEContactsDirect(u42, 500)
        val econtactsSeqOpt = econtactsJsArr.validate[Seq[EContact]].asOpt
        econtactsSeqOpt.isEmpty mustEqual false
        val econtactsSeq = econtactsSeqOpt.get
        econtactsSeq.isEmpty mustEqual false
        econtactsSeq.length mustEqual 4 // distinct

        // todo: remove queryEContacts
        var qRes = commander.queryEContacts(u42, 10, None, None)
        qRes.isEmpty mustNotEqual true
        qRes.length mustEqual 4
        qRes = commander.queryEContacts(u42, 10, Some("ray"), None)
        qRes.isEmpty mustNotEqual true
        qRes.length mustEqual 1
        qRes = commander.queryEContacts(u42, 10, Some("foo"), None)  // name and email both considered in our current alg
        qRes.isEmpty mustNotEqual true
        qRes.length mustEqual 2

        val e2 = "foo@42go.com"
        val e2Res = commander.getOrCreateEContact(u42, e2)
        e2Res.isSuccess mustEqual true

        val npeRes = commander.getOrCreateEContact(u42, null)
        npeRes.isSuccess mustEqual false

        val e1 = "foobar@42go.com"
        val e1Res = commander.getOrCreateEContact(u42, e1)
        // e1Res.isSuccess mustEqual true // todo: revisit -- insertOnDup appears not working properly in test
        e1Res mustNotEqual null
      }
    }

    "handle imports from multiple gmail accounts" in {
      implicit val system = ActorSystem("test")
      withDb(
        FakeABookRawInfoStoreModule(),
        TestSlickModule(TestDbInfo.dbInfo),
        FakeClockModule(),
        StandaloneTestActorSystemModule(),
        FakeAirbrakeModule(),
        ABookCacheModule(HashMapMemoryCacheModule())) { implicit injector =>
        val (abookController) = setup()
        val gmailOwner = GmailABookOwnerInfo(Some("123456789"), Some("42@42go.com"), Some(true), Some("42go.com"))
        val gmailUploadJson = Json.obj(
          "origin"      -> "gmail",
          "ownerId"     -> gmailOwner.id.get,
          "ownerEmail"  -> gmailOwner.email.get,
          "contacts"    -> c42
        )
        val gbookInfo:ABookInfo = abookController.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner), None, gmailUploadJson)
        gbookInfo.id.get mustEqual Id[ABookInfo](1)
        gbookInfo.origin mustEqual ABookOrigins.GMAIL
        gbookInfo.userId mustEqual u42

        val gbookInfos = abookController.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt = gbookInfos.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt.isEmpty mustEqual false
        val gBookRawInfoSeq = gbookInfoSeqOpt.get
        gBookRawInfoSeq.length mustEqual 1

        val gmailOwner2 = GmailABookOwnerInfo(Some("53"), Some("53@53go.com"), Some(true), Some("53.com"))
        val gmailUploadJson2 = Json.obj(
          "origin"      -> "gmail",
          "ownerId"     -> gmailOwner2.id.get,
          "ownerEmail"  -> gmailOwner2.email.get,
          "contacts"    -> c53
        )
        val gbookInfo2:ABookInfo = abookController.processUpload(u42, ABookOrigins.GMAIL, Some(gmailOwner2), None, gmailUploadJson2)
        gbookInfo2.id.get mustEqual Id[ABookInfo](2)
        gbookInfo2.origin mustEqual ABookOrigins.GMAIL
        gbookInfo2.userId mustEqual u42

        val gbookInfos2 = abookController.getABookRawInfosDirect(u42)
        val gbookInfoSeqOpt2 = gbookInfos2.validate[Seq[ABookRawInfo]].asOpt
        gbookInfoSeqOpt2.isEmpty mustEqual false
        val gBookRawInfoSeq2 = gbookInfoSeqOpt2.get
        gBookRawInfoSeq2.length mustEqual 2
      }
    }
  }
}

