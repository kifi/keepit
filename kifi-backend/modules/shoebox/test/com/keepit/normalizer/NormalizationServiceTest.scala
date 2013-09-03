package com.keepit.normalizer

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.scraper.{BasicArticle, FakeScraperModule}
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
import com.keepit.scraper.extractor.Extractor

class NormalizationServiceTest extends Specification with ShoeboxTestInjector {

  val fakeArticles: PartialFunction[(String, Option[Extractor]), BasicArticle] = {
    case ("http://www.linkedin.com/pub/leonard\u002dgrimaldi/12/42/2b3", Some(_)) => BasicArticle("leonard grimaldi", "whatever")
    case ("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", Some(_)) => BasicArticle("leo grimaldi", "newTrkInfo = '17558679,' + document.referrer.substr(0,128)")
    case ("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", None) => BasicArticle("leo", "some content")
    case ("http://www.linkedin.com/in/leo", None) => BasicArticle("leo", "some content")
    case ("http://www.linkedin.com/in/viviensaulue", Some(_)) => BasicArticle("vivien", "newTrkInfo = '136123062,' + document.referrer.substr(0,128)")
  }

  def updateNormalizationNow(uri: NormalizedURI, candidates: NormalizationCandidate*)(implicit injector: Injector): Option[NormalizedURI] = {
    val uriIntegrityPlugin = inject[UriIntegrityPlugin]
    val id = Await.result(normalizationService.update(uri, candidates: _*), Duration(1, SECONDS))
    uriIntegrityPlugin.batchUpdateMerge()
    id.map { db.readOnly { implicit session => uriRepo.get(_) }}
  }

  val modules = Seq(TestFortyTwoModule(), FakeDiscoveryModule(), FakeScraperModule(Some(fakeArticles)), StandaloneTestActorSystemModule(), new ScalaModule { def configure() { bind[NormalizationService].to[NormalizationServiceImpl] }})


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
        latestHttpUri.state === NormalizedURIStates.REDIRECTED
        db.readOnly { implicit session => uriRepo.getByUri("http://vimeo.com/48578814") } == Some(latestHttpsUri)
      }

      "redirect a new http://www url to an existing https:// url" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get

        val httpWWWUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.vimeo.com/48578814")) }
        updateNormalizationNow(httpWWWUri)
        val latestHttpWWWUri = db.readOnly { implicit session => uriRepo.get(httpWWWUri.id.get) }
        latestHttpWWWUri.redirect === Some(httpsUri.id.get)
        latestHttpWWWUri.state === NormalizedURIStates.REDIRECTED
        db.readOnly { implicit session => uriRepo.getByUri("http://www.vimeo.com/48578814") } == Some(httpsUri)
      }

      "upgrade an existing https:// url to a better normalization" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        httpsUri.normalization === Some(Normalization.HTTPS)
        updateNormalizationNow(httpsUri, TrustedCandidate("https://vimeo.com/48578814", Normalization.CANONICAL))
        val latestHttpsUri = db.readOnly { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.normalization === Some(Normalization.CANONICAL)
      }

      "redirect an existing canonical normalization to a most recent one" in new TestKitScope() {
        val canonicalUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        canonicalUri.normalization === Some(Normalization.CANONICAL)

        val moreRecentCanonicalUri = updateNormalizationNow(canonicalUri, TrustedCandidate("http://vimeo.com/48578814", Normalization.CANONICAL)).get
        moreRecentCanonicalUri.state !== NormalizedURIStates.REDIRECTED
        moreRecentCanonicalUri.redirect === None
        moreRecentCanonicalUri.redirectTime === None

        val redirectedCanonicalUri = db.readOnly { implicit session => uriRepo.get(canonicalUri.id.get) }
        redirectedCanonicalUri.redirect === Some(moreRecentCanonicalUri.id.get)
        redirectedCanonicalUri.state === NormalizedURIStates.REDIRECTED
      }

      "ignore a random untrusted candidate" in new TestKitScope() {
        val httpsUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        val newReference = updateNormalizationNow(httpsUri, UntrustedCandidate("http://www.iamrandom.com", Normalization.CANONICAL), UntrustedCandidate("http://www.iamsociallyrandom.com", Normalization.OPENGRAPH))
        newReference === None
      }

      "not normalize a LinkedIn private profile to its public url if ids do not match" in new TestKitScope() {
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/profile/view?id=17558679", normalization = Some(Normalization.HTTPSWWW))) }
        updateNormalizationNow(privateUri, UntrustedCandidate("http://www.linkedin.com/pub/leonard\u002dgrimaldi/12/42/2b3", Normalization.CANONICAL)) === None
      }

      "normalize a LinkedIn private profile to its public url if ids match" in new TestKitScope() {
        val httpsPublicUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", normalization = Some(Normalization.HTTPSWWW))) }
        val privateUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://www.linkedin.com/profile/view?id=17558679").get }
        val publicUri = updateNormalizationNow(privateUri, UntrustedCandidate("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", Normalization.CANONICAL)).get
        val latestPrivateUri = db.readOnly { implicit session => uriRepo.get(privateUri.id.get) }
        val latestHttpsPublicUri = db.readOnly { implicit session => uriRepo.get(httpsPublicUri.id.get) }
        publicUri.normalization === Some(Normalization.CANONICAL)
        latestPrivateUri.redirect === Some(publicUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
        latestHttpsPublicUri.redirect === Some(publicUri.id.get)
        latestHttpsPublicUri.state === NormalizedURIStates.REDIRECTED
      }

      "normalize a LinkedIn public profile to a vanity public url" in new TestKitScope() {
        val publicUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3").get }
        val vanityUri = updateNormalizationNow(publicUri, UntrustedCandidate("http://www.linkedin.com/in/leo/", Normalization.CANONICAL)).get
        val latestPublicUri = db.readOnly { implicit session => uriRepo.get(publicUri.id.get) }
        val latestPrivateUri = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("https://www.linkedin.com/profile/view?id=17558679").get }

        vanityUri.normalization === Some(Normalization.CANONICAL)
        vanityUri.url === "http://www.linkedin.com/in/leo"
        latestPrivateUri.redirect === Some(vanityUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
        latestPublicUri.redirect === Some(vanityUri.id.get)
        latestPublicUri.state === NormalizedURIStates.REDIRECTED
      }

      "normalize a French LinkedIn private profile to a vanity public url" in new TestKitScope() {
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://fr.linkedin.com/profile/view?id=136123062")) }
        val vanityUri = updateNormalizationNow(privateUri, UntrustedCandidate("http://fr.linkedin.com/in/viviensaulue", Normalization.CANONICAL)).get
        val latestPrivateUri = db.readOnly { implicit session => uriRepo.get(privateUri.id.get) }
        vanityUri.normalization === Some(Normalization.CANONICAL)
        vanityUri.url === "http://www.linkedin.com/in/viviensaulue"
        latestPrivateUri.redirect === Some(vanityUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
      }

      "find a variation with an upgraded normalization" in new TestKitScope() {
        val canonicalVariation = db.readOnly { implicit session => uriRepo.getByNormalizedUrl("http://www.linkedin.com/in/viviensaulue").get }
        val httpsUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/in/viviensaulue")) }
        updateNormalizationNow(httpsUri, UntrustedCandidate("http://fr.linkedin.com/in/viviensaulue", Normalization.CANONICAL)) == Some(canonicalVariation)
        val latestHttpsUri = db.readOnly { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.redirect === Some(canonicalVariation.id.get)
        latestHttpsUri.state === NormalizedURIStates.REDIRECTED
      }

      "shutdown shared actor system" in {
        system.shutdown()
      }
    }
  }
}
