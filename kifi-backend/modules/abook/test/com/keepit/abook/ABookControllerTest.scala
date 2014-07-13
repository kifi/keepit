package com.keepit.abook

import com.keepit.common.mail.EmailAddress
import org.specs2.mutable._
import com.keepit.common.db.slick.Database
import com.keepit.abook.store.ABookRawInfoStore
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, TestDbInfo, Id }
import play.api.libs.json._
import com.keepit.common.actor.{ TestActorSystemModule, StandaloneTestActorSystemModule }
import play.api.libs.json.JsArray
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ABookCacheModule }
import scala.Some
import com.keepit.common.healthcheck.FakeAirbrakeModule
import akka.actor.ActorSystem
import com.keepit.shoebox.FakeShoeboxServiceModule
import play.api.test.{ FakeHeaders, FakeRequest }
import play.api.test.Helpers._
import play.api.db.DB
import java.sql.Driver
import scala.concurrent.Await
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.typeahead.TypeaheadHit
import com.keepit.abook.controllers.ABookController

class ABookControllerTest extends Specification with ABookApplicationInjector with ABookTestHelper {

  val modules = Seq(
    ABookCacheModule(HashMapMemoryCacheModule()),
    TestABookServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeSimpleQueueModule()
  )

  "abook controller" should {

    "support mobile (ios) upload" in {
      running(new ABookApplication(modules: _*)) {
        val uploadRoute = com.keepit.abook.controllers.routes.ABookController.uploadContacts(Id[User](1), ABookOrigins.IOS).url
        uploadRoute === "/internal/abook/ios/uploadContacts?userId=1"
        val payload = iosUploadJson
        val uploadRequest = FakeRequest("POST", uploadRoute, FakeHeaders(Seq("Content-Type" -> Seq("application/json"))), body = payload)
        val controller = inject[ABookController] // setup
        var result = controller.uploadContacts(Id[User](1), ABookOrigins.IOS)(uploadRequest)
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
        var nWait = 0
        while (abookInfo.state != ABookInfoStates.ACTIVE && nWait < 10) {
          nWait += 1
          result = controller.getABookInfo(Id[User](1), abookInfo.id.get)(FakeRequest())
          status(result) must equalTo(OK)
          contentType(result) must beSome("application/json")
          val s = contentAsString(result)
          s !== null
          abookInfo = Json.fromJson[ABookInfo](Json.parse(s)).get
          Thread.sleep(200)
        }
        abookInfo.state === ABookInfoStates.ACTIVE
      }
    }

    "support prefixQuery" in {
      running(new ABookApplication(modules: _*)) {
        val uploadRoute = com.keepit.abook.controllers.routes.ABookController.uploadContacts(Id[User](1), ABookOrigins.IOS).url
        uploadRoute === "/internal/abook/ios/uploadContacts?userId=1"
        val payload = iosUploadJson
        val uploadRequest = FakeRequest("POST", uploadRoute, FakeHeaders(Seq("Content-Type" -> Seq("application/json"))), body = payload)
        val controller = inject[ABookController] // setup
        var result = controller.uploadContacts(Id[User](1), ABookOrigins.IOS)(uploadRequest)
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
        var nWait = 0
        while (abookInfo.state != ABookInfoStates.ACTIVE && nWait < 10) {
          nWait += 1
          result = controller.getABookInfo(Id[User](1), abookInfo.id.get)(FakeRequest())
          status(result) must equalTo(OK)
          contentType(result) must beSome("application/json")
          val s = contentAsString(result)
          s !== null
          abookInfo = Json.fromJson[ABookInfo](Json.parse(s)).get
          Thread.sleep(200)
        }
        abookInfo.state === ABookInfoStates.ACTIVE

        // get all
        var resultQ = controller.getContactsByUser(Id[User](1), pageSize = Some(10))(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        var content = contentAsString(resultQ)
        content !== null
        var contacts = Json.fromJson[Seq[RichContact]](Json.parse(content)).get
        println(s"[query-all] result(${contacts.length}):${contacts.mkString(",")}")
        contacts !== null
        contacts.isEmpty !== true
        contacts.length === 4

        // get 0
        resultQ = controller.prefixQuery(Id[User](1), "lolcat", Some(10))(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        var hits = Json.fromJson[Seq[TypeaheadHit[RichContact]]](Json.parse(content)).get
        println(s"[query-0] result(${hits.length}):${hits.mkString(",")}")
        hits !== null
        hits.isEmpty === true
        hits.length === 0

        // search
        resultQ = controller.prefixQuery(Id[User](1), "ray", Some(10))(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        hits = Json.fromJson[Seq[TypeaheadHit[RichContact]]](Json.parse(content)).get
        println(s"[query-search] result(${hits.length}):${hits.mkString(",")}")
        hits !== null
        hits.isEmpty !== true
        hits.length === 1

        // limit
        resultQ = controller.prefixQuery(Id[User](1), "fo", Some(3))(FakeRequest())
        status(resultQ) must equalTo(OK)
        contentType(resultQ) must beSome("application/json")
        content = contentAsString(resultQ)
        content !== null
        hits = Json.fromJson[Seq[TypeaheadHit[RichContact]]](Json.parse(content)).get
        println(s"[query-limit] result(${hits.length}):${hits.mkString(",")}")
        hits !== null
        hits.isEmpty !== true
        hits.length === 3
      }
    }

    "support hide email from user" in {
      running(new ABookApplication(modules: _*)) {
        val hideEmailRoute = com.keepit.abook.controllers.routes.ABookController.hideEmailFromUser(Id[User](1), EmailAddress("tan@kifi.com"))
        hideEmailRoute.toString === "/internal/abook/1/hideEmailFromUser?email=tan%40kifi.com"
        val controller = inject[ABookController] // setup
        val result = controller.hideEmailFromUser(Id[User](1), EmailAddress("tan@kifi.com"))(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        var content = contentAsString(result)
        content !== null
      }
    }

  }

}
