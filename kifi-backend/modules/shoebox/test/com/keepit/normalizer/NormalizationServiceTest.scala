
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
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.inject.TestFortyTwoModule
import com.keepit.integrity.UriIntegrityPlugin
import com.google.inject.Injector

class NormalizationServiceTest extends Specification with ShoeboxTestInjector {

  val fakeSignatures: PartialFunction[String, Signature] = {
    case "https://vimeo.com/48578814" => Signature("woods")
    case "http://vimeo.com/48578814" => Signature("woods")
    case "http://www.vimeo.com/48578814" => Signature("woods")
  }

  def updateNormalizationNow(uri: NormalizedURI, candidates: NormalizationCandidate*)(implicit injector: Injector): Option[NormalizedURI] = {
    val uriIntegrityPlugin = inject[UriIntegrityPlugin]
    val result = Await.result(normalizationService.update(uri, candidates: _*), Duration(1, SECONDS))
    uriIntegrityPlugin.batchUpdateMerge()
    result
  }

  val modules = Seq(TestFortyTwoModule(), FakeDiscoveryModule(), FakeScraperModule(Some(fakeSignatures)), StandaloneTestActorSystemModule(), new ScalaModule { def configure() { bind[NormalizationService].to[NormalizationServiceImpl] }})


  "NormalizationService" should {


    withDb(modules:_*) { implicit injector =>
      implicit val system = inject[ActorSystem]

      "normalize a new http:// url to HTTP" in new TestKitScope() {
        val httpUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://vimeo.com/48578814")) }
        httpUri.normalization === None
        updateNormalizationNow(httpUri)
        val latestHttpUri = db.readOnly { implicit session => uriRepo.get(httpUri.id.get) }
        latestHttpUri.normalization === Some(Normalization.HTTP)
      }

      "does not normalize an http:// url to HTTP twice" in new TestKitScope() {
        val httpUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get
        httpUri.normalization === Some(Normalization.HTTP)
        updateNormalizationNow(httpUri, TrustedCandidate("http://vimeo.com/48578814", Normalization.HTTP)) == None
      }

      "redirect an existing http url to a new https:// url" in new TestKitScope() {
        val httpUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get

        val httpsUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
        updateNormalizationNow(httpsUri)
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
        updateNormalizationNow(httpWWWUri)
        val latestHttpWWWUri = db.readOnly { implicit session => uriRepo.get(httpWWWUri.id.get) }
        latestHttpWWWUri.redirect === Some(httpsUri.id.get)
        latestHttpWWWUri.state === NormalizedURIStates.INACTIVE
        db.readOnly { implicit session => uriRepo.getByUri("http://www.vimeo.com/48578814") } == Some(httpsUri)
      }

      "upgrade an existing https:// url to a better normalization" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        httpsUri.normalization === Some(Normalization.HTTPS)
        updateNormalizationNow(httpsUri, TrustedCandidate("https://vimeo.com/48578814", Normalization.CANONICAL))
        val latestHttpsUri = db.readOnly { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.normalization === Some(Normalization.CANONICAL)
      }

      "ignore a random untrusted candidate" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        val newReference = updateNormalizationNow(httpsUri, UntrustedCandidate("http://www.iamrandom.com", Normalization.CANONICAL), UntrustedCandidate("http://www.iamsociallyrandom.com", Normalization.OPENGRAPH))
        newReference === None
      }

      "shutdown shared actor system" in {
        system.shutdown()
      }
    }
  }
}

