package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.controllers.client.KeepExportController
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.iteratee.{ Enumeratee, Enumerator, Iteratee }
import play.api.mvc.Call
import play.api.test.FakeRequest

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class KeepExportControllerTest extends Specification with ShoeboxTestInjector {
  def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[KeepExportController]
  private def route = com.keepit.controllers.client.routes.KeepExportController

  val modules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "KeepExportController" should {
    "demonstrate how to use enumerators and enumeratees" in {
      val source: Enumerator[Int] = Enumerator.enumerate(Stream.range(1, 1000))
      val transformer: Enumeratee[Int, String] = Enumeratee.map[Int](x => (x * x).toString)
      val consumer: Iteratee[String, Int] = Iteratee.fold[String, Int](0)(_ + _.length)
      val result = source.through(transformer).run(consumer)
      Await.result(result, Duration.Inf) === Seq.range(1, 1000).map(x => x * x).mkString.length
      1 === 1
    }
  }
}
