package com.keepit.eliza.ws

import akka.actor.{ ActorSystem, Scheduler }
import com.google.inject.Injector
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialAuthenticatorPlugin }
import com.keepit.inject.InjectorProvider
import com.keepit.test.{ TestInjectorProvider, TestInjector }
import org.specs2.mutable.Specification
import org.specs2.matcher.{ MatchResult, Expectable, Matcher }
import org.specs2.time.NoTimeConversions
import play.api.libs.iteratee.{ Step, Input, Iteratee, Enumerator }
import play.api.libs.json.JsArray
import play.api.test.FakeRequest

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Promise, Future }
import akka.pattern.after

trait WebSocketTest extends Specification with TestInjectorProvider with NoTimeConversions {
  this: InjectorProvider =>

  def delayedEOF[E](implicit scheduler: Scheduler, ec: ExecutionContext): Enumerator[E] = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A]): Future[Iteratee[E, A]] =
      i.fold {
        case Step.Cont(k) => after(10 seconds, scheduler)(Future(k(Input.EOF))(ec))(ec)
        case _ => Future.successful(i)
      }(ec)
  }

  def feedToSocket(inputs: Seq[JsArray])(implicit injector: Injector): Future[Seq[JsArray]] = {
    val injected = inject[SharedWsMessagingController]
    implicit val scheduler = inject[ActorSystem].scheduler

    val feed = Enumerator.enumerate(inputs) andThen delayedEOF[JsArray] // use a delayed end so that everything can process in time
    val resultsPromise = Promise[Seq[JsArray]]()

    val out = Iteratee.getChunks[JsArray].map { outputs =>
      resultsPromise.success(outputs)
    }.recover {
      case thrown: Throwable =>
        resultsPromise.failure(thrown)
    }.map(_ => ())

    injected.websocket(None, None).f(FakeRequest("GET", "/ws?sid=" + FakeSecureSocial.FAKE_SID)).flatMap {
      case Right(fn) =>
        fn(feed, out)
        resultsPromise.future
      case Left(result) =>
        resultsPromise.failure(new RuntimeException("Result " + result + " was not expected"))
        resultsPromise.future
    }
  }

  class WebSocketMatcher(m: Matcher[Seq[JsArray]], retries: Int = 0, timeout: FiniteDuration = 60.seconds)(implicit injector: Injector) extends Matcher[Seq[JsArray]] {

    override def apply[N <: Seq[JsArray]](t: Expectable[N]): MatchResult[N] = {
      m.await(retries, timeout).apply(t.map[Future[Seq[JsArray]]] { value =>
        feedToSocket(value)
      }).asInstanceOf[MatchResult[N]]
    }

    def after(newRetries: Int = 0, newTimeout: FiniteDuration = 60.seconds) = new WebSocketMatcher(m, newRetries, newTimeout)

  }

  def leadToSocketOutput(m: Matcher[Seq[JsArray]])(implicit injector: Injector) = new WebSocketMatcher(m)

}
