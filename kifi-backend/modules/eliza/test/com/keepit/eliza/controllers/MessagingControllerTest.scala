package com.keepit.eliza.controllers

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.eliza.model._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.realtime.{ FakeAppBoyModule }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ DbInjectionHelper, ElizaTestInjector }
import org.joda.time.DateTime
import org.specs2.execute.FailureException
import org.specs2.matcher.MatchResult
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsArray, JsNull, Json }
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test.{ FakeHeaders, FakeRequest }

import scala.concurrent.Future

class MessagingControllerTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with DbInjectionHelper {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeAppBoyModule(),
    FakeUserActionsModule(),
    FakeCryptoModule(),
    FakeElizaStoreModule(),
    FakeHttpClientModule(),
    FakeActorSystemModule(),
    FakeRoverServiceClientModule()
  )

  val initialDateTime = new DateTime(2015, 1, 22, 0, 0)
  val seconds = new AtomicInteger(0)
  def nextTime = initialDateTime.minusSeconds(seconds.getAndIncrement)

  def createMessageThread(title: String, creator: Id[User])(implicit rw: RWSession, injector: Injector) = {
    MessageThreadFactory.thread().withOnlyStarter(creator).withTitle(title).saved
  }

  def createUserThread(thread: MessageThread, userId: Id[User])(implicit rw: RWSession, injector: Injector) = {
    inject[UserThreadRepo].save(UserThread.forMessageThread(thread)(userId).copy(notificationUpdatedAt = nextTime))
  }

  def createMessage(thread: MessageThread, senderUserId: Id[User], text: String)(implicit rw: RWSession, injector: Injector) = {
    MessageFactory.message().withThread(thread).from(MessageSender.User(senderUserId)).withText(text).withCreatedAt(nextTime).saved
  }

  "MessagingController" should {
    "getUnreadNotifications" in {
      withDb(modules: _*) { implicit injector =>
        val messagingController = inject[MessagingController]
        val userId = Id[User](42)
        val call = com.keepit.eliza.controllers.internal.routes.MessagingController.getUnreadNotifications(userId, 10)
        call.url === "/internal/eliza/getUnreadNotifications?userId=42&howMany=10"
        call.method === "GET"

        val result1 = messagingController.getUnreadNotifications(userId, 10)(FakeRequest(call))
        status(result1) === OK
        contentAsJson(result1) === JsArray()

        val sender1 = Id[User](43)
        val sender2 = Id[User](44)

        db.readWrite { implicit rw =>
          Seq("HackerNews", "Reddit").zipWithIndex.map {
            case (title, idx) =>
              val thread = MessageThreadFactory.thread().withTitle(title).withOnlyStarter(sender1).saved
              val userThread = UserThreadFactory.userThread().withThread(thread).forUserId(userId).unread().saved
              val messages = Seq(
                createMessage(thread, sender1, s"check out $title"),
                createMessage(thread, sender2, s"look here $title")
              )
              (thread, userThread, messages)
          }
        }

        val result2 = messagingController.getUnreadNotifications(userId, 10)(FakeRequest(call))
        val Seq(t1, t2) = contentAsJson(result2).as[Seq[UserThreadView]]

        def assertSender(view: MessageView)(p: Any => MatchResult[Any]): MatchResult[Any] = view.from match {
          case MessageSenderUserView(id) => p(id)
          case _ => throw new FailureException(failure(s"wrong kind ${view.from.kind}"))
        }

        t1.pageTitle must beSome("Reddit")
        t1.messages.size === 2
        t1.messages(0).messageText === "check out Reddit"
        assertSender(t1.messages(0)) { _ === sender1 }
        assertSender(t1.messages(1)) { _ === sender2 }

        t2.pageTitle must beSome("HackerNews")
        t2.messages.size === 2
        t2.messages(0).messageText === "check out HackerNews"
        assertSender(t2.messages(0)) { _ === sender1 }
        assertSender(t2.messages(1)) { _ === sender2 }
      }
    }
  }
}
