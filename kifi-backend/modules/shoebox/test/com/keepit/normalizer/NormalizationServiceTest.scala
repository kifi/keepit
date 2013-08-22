
package com.keepit.normalizer

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.scraper.{Signature, FakeScraperModule}
import com.keepit.model.{NormalizedURIStates, NormalizedURI, Normalization}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.akka.TestKitScope
import akka.actor.ActorSystem

class NormalizationServiceTest extends Specification with ShoeboxTestInjector {

  val fakeSignatures: PartialFunction[String, Signature] = {
    case "https://vimeo.com/48578814" => Signature("woods")
    case "http://vimeo.com/48578814" => Signature("woods")
    case "http://www.vimeo.com/48578814" => Signature("woods")
  }

  val modules = Seq(FakeScraperModule(Some(fakeSignatures)), StandaloneTestActorSystemModule(), new ScalaModule { def configure() { bind[NormalizationService].to[NormalizationServiceImpl] }})

  "NormalizationService" should {


    withDb(modules:_*) { implicit injector =>
      implicit val system = inject[ActorSystem]

      "normalize a new http:// url to HTTP" in new TestKitScope() {
        val httpUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://vimeo.com/48578814")) }
        httpUri.normalization === None
        Await.result(normalizationService.update(httpUri), 1 second)
        val latestHttpUri = db.readOnly { implicit session => uriRepo.get(httpUri.id.get) }
        latestHttpUri.normalization === Some(Normalization.HTTP)
      }

      "does not normalize an http:// url to HTTP twice" in new TestKitScope() {
        val httpUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get
        httpUri.normalization === Some(Normalization.HTTP)
        Await.result(normalizationService.update(httpUri, TrustedCandidate("http://vimeo.com/48578814", Normalization.HTTP)), 1 second) === None
      }

      "redirect an existing http url to a new https:// url" in new TestKitScope() {
        val httpUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get

        val httpsUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
        Await.result(normalizationService.update(httpsUri), 1 second)
        val latestHttpsUri = db.readOnly { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.normalization === Some(Normalization.HTTPS)

        val latestHttpUri = db.readOnly { implicit session => uriRepo.get(httpUri.id.get) }
        latestHttpUri.redirect === Some(latestHttpsUri.id.get)
        latestHttpUri.state === NormalizedURIStates.INACTIVE
        db.readOnly { implicit session => uriRepo.getByUri("http://vimeo.com/48578814") } == Some(latestHttpsUri)
      }

      "redirect a new http://www url to an existing https:// url" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get

        val httpWWWUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.vimeo.com/48578814")) }
        Await.result(normalizationService.update(httpWWWUri), 1 second)
        val latestHttpWWWUri = db.readOnly { implicit session => uriRepo.get(httpWWWUri.id.get) }
        latestHttpWWWUri.redirect === Some(httpsUri.id.get)
        latestHttpWWWUri.state === NormalizedURIStates.INACTIVE
        db.readOnly { implicit session => uriRepo.getByUri("http://www.vimeo.com/48578814") } == Some(httpsUri)
      }

      "upgrade an existing https:// url to a better normalization" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        httpsUri.normalization === Some(Normalization.HTTPS)
        Await.result(normalizationService.update(httpsUri, TrustedCandidate("https://vimeo.com/48578814", Normalization.CANONICAL)), 1 second)
        val latestHttpsUri = db.readOnly { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.normalization === Some(Normalization.CANONICAL)
      }

      "ignore a random untrusted candidate" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        val newReference = Await.result(normalizationService.update(httpsUri, UntrustedCandidate("http://www.iamrandom.com", Normalization.CANONICAL), UntrustedCandidate("http://www.iamsociallyrandom.com", Normalization.OPENGRAPH)), 1 second)
        newReference === None
      }

      "shutdown shared actor system" in {
        system.shutdown()
      }
    }
  }
}

