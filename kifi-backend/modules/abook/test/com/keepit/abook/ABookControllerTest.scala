package com.keepit.abook


import org.specs2.mutable._
import com.keepit.test.DbTestInjector
import com.google.inject.Injector
import com.keepit.common.db.slick.Database
import com.keepit.abook.store.ABookRawInfoStore
import com.keepit.model._
import com.keepit.common.db.{ExternalId, TestDbInfo, Id, TestSlickModule}
import play.api.libs.json._
import com.keepit.common.actor.{TestActorSystemModule, StandaloneTestActorSystemModule}
import play.api.libs.json.JsArray
import com.keepit.common.time.FakeClockModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.ABookCacheModule
import play.api.libs.json.JsString
import scala.Some
import com.keepit.common.healthcheck.FakeAirbrakeModule
import akka.actor.ActorSystem
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import play.api.test.{FakeHeaders, FakeRequest}
import play.api.test.Helpers._
import com.keepit.social.{SocialNetworks, SocialId}
import com.keepit.common.controller.{ActionAuthenticator, FakeActionAuthenticator}
import play.api.db.DB
import play.api.Play.current
import play.api.mvc.Result
import java.sql.{Driver, DriverManager}
import scala.concurrent.Await

class ABookControllerTest extends Specification with ABookApplicationInjector with ABookTestHelper {

  val modules = Seq()

//  def setup()(implicit injector:Injector) = {
//    val db = inject[Database]
//    val abookInfoRepo = inject[ABookInfoRepo]
//    val contactRepo = inject[ContactRepo]
//    val econtactRepo = inject[EContactRepo]
//    val oauth2TokenRepo = inject[OAuth2TokenRepo]
//    val contactsUpdater = inject[ContactsUpdaterPlugin]
//    val s3 = inject[ABookRawInfoStore]
//    val aa = inject[ActionAuthenticator]
//    val commander = new ABookCommander(db, s3, abookInfoRepo, contactRepo, econtactRepo, contactsUpdater)
//    val controller = new ABookController(aa, db, s3, abookInfoRepo, contactRepo, econtactRepo, oauth2TokenRepo, commander, contactsUpdater)
//    controller
//  }

  "abook controller" should {
    "support mobile (ios) upload + query" in {
      running(new ABookApplication(modules:_*)) {
        val uploadRoute = com.keepit.abook.routes.ABookController.upload(Id[User](1), ABookOrigins.IOS).url
        uploadRoute === "/internal/abook/ios/uploadForUser?userId=1"
        val payload = iosUploadJson
        val uploadRequest = FakeRequest("POST", uploadRoute, FakeHeaders(Seq("Content-Type" -> Seq("application/json"))), body = payload)
        val controller = inject[ABookController] // setup
        var result = controller.upload(Id[User](1), ABookOrigins.IOS)(uploadRequest)
        println(s"[ios-upload] result=$result")
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val s = contentAsString(result)
        s !== null
        val json = Json.parse(s)
        var abookInfo = Json.fromJson[ABookInfo](json).get
        println(s"[ios-upload] abookInfo=$abookInfo")
        abookInfo.origin === ABookOrigins.IOS
        abookInfo.numContacts must beSome(3)
        abookInfo.state !== ABookInfoStates.UPLOAD_FAILURE

        // sanity check
        // Todo(stephen) Figure out why this is failing randomly
        // var nWait = 0
        // while (abookInfo.state != ABookInfoStates.ACTIVE && nWait < 10) {
        //   nWait += 1
        //   result = controller.getABookInfo(Id[User](1), abookInfo.id.get)(FakeRequest())
        //   status(result) must equalTo(OK)
        //   contentType(result) must beSome("application/json")
        //   val s = contentAsString(result)
        //   s !== null
        //   abookInfo = Json.fromJson[ABookInfo](Json.parse(s)).get
        //   Thread.sleep(200)
        // }
        // abookInfo.state === ABookInfoStates.ACTIVE

        // get all
        var resultQ = controller.queryEContacts(Id[User](1), 10, None, None)(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        var content = contentAsString(resultQ)
        content !== null
        var econtacts = Json.fromJson[Seq[EContact]](Json.parse(content)).get
        println(s"[query-all] result(${econtacts.length}):${econtacts.mkString(",")}")
        econtacts !== null
        econtacts.isEmpty !== true
        econtacts.length  === 4

        // get 0
        resultQ = controller.queryEContacts(Id[User](1), 10, Some("lolcat"), None)(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        econtacts = Json.fromJson[Seq[EContact]](Json.parse(content)).get
        println(s"[query-0] result(${econtacts.length}):${econtacts.mkString(",")}")
        econtacts !== null
        econtacts.isEmpty === true
        econtacts.length  === 0

        // search
        resultQ = controller.queryEContacts(Id[User](1), 10, Some("ray"), None)(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        econtacts = Json.fromJson[Seq[EContact]](Json.parse(content)).get
        println(s"[query-search] result(${econtacts.length}):${econtacts.mkString(",")}")
        econtacts !== null
        econtacts.isEmpty !== true
        econtacts.length  === 1

        // limit
        resultQ = controller.queryEContacts(Id[User](1), 2, Some("42go"), None)(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        econtacts = Json.fromJson[Seq[EContact]](Json.parse(content)).get
        println(s"[query-limit] result(${econtacts.length}):${econtacts.mkString(",")}")
        econtacts !== null
        econtacts.isEmpty !== true
        econtacts.length  === 2

        // after
        resultQ = controller.queryEContacts(Id[User](1), 10, Some("42go"), Some("email/bar@42go.com"))(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        econtacts = Json.fromJson[Seq[EContact]](Json.parse(content)).get
        println(s"[query-after] result(${econtacts.length}):${econtacts.mkString(",")}")
        econtacts.isEmpty !== true
        econtacts.find(_.email == "bar@42go.com").isDefined !== true
        econtacts !== null
      }
    }
  }

}
