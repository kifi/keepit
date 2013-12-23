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

class ABookControllerTest extends Specification with ABookApplicationInjector with ABookUploadTestHelper {

  val modules = Seq(
  )

  def setup()(implicit injector:Injector) = {
    val db = inject[Database]
    val abookInfoRepo = inject[ABookInfoRepo]
    val contactRepo = inject[ContactRepo]
    val econtactRepo = inject[EContactRepo]
    val oauth2TokenRepo = inject[OAuth2TokenRepo]
    val contactsUpdater = inject[ContactsUpdaterPlugin]
    val s3 = inject[ABookRawInfoStore]
    val aa = inject[ActionAuthenticator]
    val commander = new ABookCommander(db, s3, abookInfoRepo, contactRepo, econtactRepo, contactsUpdater)
    val controller = new ABookController(aa, db, s3, abookInfoRepo, contactRepo, econtactRepo, oauth2TokenRepo, commander, contactsUpdater)
    controller
  }

  "abook controller" should {
    "handle ios upload" in {
      running(new ABookApplication(modules:_*)) {
        val uploadRoute = com.keepit.abook.routes.ABookController.upload(Id[User](1), ABookOrigins.IOS).url
        uploadRoute === "/internal/abook/ios/uploadForUser?userId=1"
        val payload = iosUploadJson
        val uploadRequest = FakeRequest("POST", uploadRoute, FakeHeaders(Seq("Content-Type" -> Seq("application/json"))), body = payload)
        val controller = setup() // inject[ABookController]
        val result = controller.upload(Id[User](1), ABookOrigins.IOS)(uploadRequest)
        println(s"[ios-upload] result=$result")
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        contentAsString(result) !== null
        val s = contentAsString(result)
        val json = Json.parse(s)
        val abookInfo = Json.fromJson[ABookInfo](json).get
        println(s"[ios-upload] abookInfo=$abookInfo")
        abookInfo.origin === ABookOrigins.IOS
        abookInfo.numContacts must beSome(3)
        abookInfo.state !== ABookInfoStates.UPLOAD_FAILURE
      }
    }
  }

}
