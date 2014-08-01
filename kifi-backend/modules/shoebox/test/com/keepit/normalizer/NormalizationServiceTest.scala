package com.keepit.normalizer

import com.google.inject.Injector
import com.keepit.akka.TestKitScope
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.inject.FakeFortyTwoModule
import com.keepit.integrity.UriIntegrityPlugin
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeSignatureBuilder, Signature, BasicArticle }
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration._

class NormalizationServiceTest extends TestKitScope with SpecificationLike with ShoeboxTestInjector {

  val fakeArticles: PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle] = {
    case (url @ "http://www.linkedin.com/pub/leonard\u002dgrimaldi/12/42/2b3", Some(_)) => BasicArticle("leonard grimaldi", "whatever", signature = fakeSignature("whatever"), destinationUrl = url)
    case (url @ "http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", Some(_)) => BasicArticle("leo grimaldi", "17558679", signature = fakeSignature("17558679"), destinationUrl = url)
    case (url @ "http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", None) => BasicArticle("leo", "some content", signature = fakeSignature("some content"), destinationUrl = url)
    case (url @ "http://www.linkedin.com/in/leo", None) => BasicArticle("leo", "some content", signature = fakeSignature("some content"), destinationUrl = url)
    case (url @ "http://www.linkedin.com/in/viviensaulue", Some(_)) => BasicArticle("vivien", "136123062", signature = fakeSignature("136123062"), destinationUrl = url)
  }

  private def fakeSignature(text: String): Signature = {
    new FakeSignatureBuilder().add(text).build
  }

  def updateNormalizationNow(uri: NormalizedURI, candidates: NormalizationCandidate*)(implicit injector: Injector): Option[NormalizedURI] = {
    db.readWrite { implicit session =>
      inject[UrlPatternRuleRepo].loadCache()
    }
    val uriIntegrityPlugin = inject[UriIntegrityPlugin]
    val id = Await.result(normalizationService.update(NormalizationReference(uri), candidates: _*), 5 seconds)
    uriIntegrityPlugin.batchURIMigration()
    id.map { db.readOnlyMaster { implicit session => uriRepo.get(_) } }
  }

  val modules = Seq(
    FakeFortyTwoModule(),
    FakeDiscoveryModule(),
    FakeScrapeSchedulerModule(Some(fakeArticles)),
    FakeAirbrakeModule(),
    StandaloneTestActorSystemModule(),
    FakeElizaServiceClientModule(),
    FakeKeepImportsModule(),
    new ScalaModule {
      def configure() {
        bind[NormalizationService].to[NormalizationServiceImpl]
        bind[UrlPatternRuleRepo].to[UrlPatternRuleRepoImpl]
      }
    })

  "NormalizationService" should {

    withDb(modules: _*) { implicit injector =>

      "normalize a new http:// url to HTTP" in {
        val httpUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://vimeo.com/48578814")) }
        httpUri.normalization === None
        updateNormalizationNow(httpUri)
        val latestHttpUri = db.readOnlyMaster { implicit session => uriRepo.get(httpUri.id.get) }
        latestHttpUri.normalization === Some(Normalization.HTTP)
      }

      "does not normalize an http:// url to HTTP twice" in {
        val httpUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get
        httpUri.normalization === Some(Normalization.HTTP)
        updateNormalizationNow(httpUri, VerifiedCandidate("http://vimeo.com/48578814", Normalization.HTTP)) === None
      }

      "redirect an existing http url to a new https:// url" in {
        val httpUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get

        val httpsUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
        updateNormalizationNow(httpsUri)
        val latestHttpsUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.normalization === Some(Normalization.HTTPS)

        val latestHttpUri = db.readOnlyMaster { implicit session => uriRepo.get(httpUri.id.get) }
        latestHttpUri.redirect === Some(latestHttpsUri.id.get)
        latestHttpUri.state === NormalizedURIStates.REDIRECTED
        db.readOnlyMaster { implicit session => normalizedURIInterner.getByUri("http://vimeo.com/48578814") } === Some(latestHttpsUri)
      }

      "redirect a new http://www url to an existing https:// url" in {
        val httpsUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get

        val httpWWWUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.vimeo.com/48578814")) }
        updateNormalizationNow(httpWWWUri)
        val latestHttpWWWUri = db.readOnlyMaster { implicit session => uriRepo.get(httpWWWUri.id.get) }
        latestHttpWWWUri.normalization === Some(Normalization.HTTPWWW)
        latestHttpWWWUri.redirect === Some(httpsUri.id.get)
        latestHttpWWWUri.state === NormalizedURIStates.REDIRECTED
        db.readOnlyMaster { implicit session => normalizedURIInterner.getByUri("http://www.vimeo.com/48578814") }.map(_.id) === Some(httpsUri.id)
      }

      "upgrade an existing https:// url to a better normalization" in {
        val httpsUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        httpsUri.normalization === Some(Normalization.HTTPS)
        updateNormalizationNow(httpsUri, VerifiedCandidate("https://vimeo.com/48578814", Normalization.CANONICAL))
        val latestHttpsUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.normalization === Some(Normalization.CANONICAL)
      }

      "redirect an existing canonical normalization to a most recent one" in {
        val canonicalUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        canonicalUri.normalization === Some(Normalization.CANONICAL)

        val moreRecentCanonicalUri = updateNormalizationNow(canonicalUri, VerifiedCandidate("http://vimeo.com/48578814", Normalization.CANONICAL)).get
        moreRecentCanonicalUri.state !== NormalizedURIStates.REDIRECTED
        moreRecentCanonicalUri.redirect === None
        moreRecentCanonicalUri.redirectTime === None

        val redirectedCanonicalUri = db.readOnlyMaster { implicit session => uriRepo.get(canonicalUri.id.get) }
        redirectedCanonicalUri.redirect === Some(moreRecentCanonicalUri.id.get)
        redirectedCanonicalUri.state === NormalizedURIStates.REDIRECTED
      }

      "ignore a random untrusted candidate" in {
        val httpsUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
        val newReference = updateNormalizationNow(httpsUri, UntrustedCandidate("http://www.iamrandom.com", Normalization.CANONICAL), UntrustedCandidate("http://www.iamsociallyrandom.com", Normalization.OPENGRAPH))
        newReference === None
      }

      "not normalize a LinkedIn private profile to its public url if ids do not match" in {
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/profile/view?id=17558679", normalization = Some(Normalization.HTTPSWWW))) }
        updateNormalizationNow(privateUri, UntrustedCandidate("http://www.linkedin.com/pub/leonard\u002dgrimaldi/12/42/2b3", Normalization.CANONICAL)) === None
      }

      "normalize a LinkedIn private profile to its public url if ids match" in {
        val httpsPublicUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", normalization = Some(Normalization.HTTPSWWW))) }
        val privateUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://www.linkedin.com/profile/view?id=17558679").get }
        val publicUri = updateNormalizationNow(privateUri, UntrustedCandidate("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", Normalization.CANONICAL)).get
        val latestPrivateUri = db.readOnlyMaster { implicit session => uriRepo.get(privateUri.id.get) }
        val latestHttpsPublicUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsPublicUri.id.get) }
        publicUri.normalization === Some(Normalization.CANONICAL)
        latestPrivateUri.redirect === Some(publicUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
        latestHttpsPublicUri.redirect === Some(publicUri.id.get)
        latestHttpsPublicUri.state === NormalizedURIStates.REDIRECTED
      }

      "normalize a LinkedIn public profile to a vanity public url if this url is trusted" in {
        val publicUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3").get }
        updateNormalizationNow(publicUri, UntrustedCandidate("http://www.linkedin.com/in/leo/", Normalization.CANONICAL)) === None
        db.readWrite { implicit session => urlPatternRuleRepo.save(UrlPatternRule(pattern = LinkedInNormalizer.linkedInCanonicalPublicProfile.toString(), trustedDomain = Some("""^https?://([a-z]{2,3})\.linkedin\.com/.*"""))) }
        val vanityUri = updateNormalizationNow(publicUri, UntrustedCandidate("http://www.linkedin.com/in/leo/", Normalization.CANONICAL)).get
        val latestPublicUri = db.readOnlyMaster { implicit session => uriRepo.get(publicUri.id.get) }
        val latestPrivateUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://www.linkedin.com/profile/view?id=17558679").get }

        vanityUri.normalization === Some(Normalization.CANONICAL)
        vanityUri.url === "http://www.linkedin.com/in/leo"
        latestPrivateUri.redirect === Some(vanityUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
        latestPublicUri.redirect === Some(vanityUri.id.get)
        latestPublicUri.state === NormalizedURIStates.REDIRECTED
      }

      "normalize a French LinkedIn private profile to a vanity public url" in {
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://fr.linkedin.com/profile/view?id=136123062")) }
        val vanityUri = updateNormalizationNow(privateUri, UntrustedCandidate("http://fr.linkedin.com/in/viviensaulue", Normalization.CANONICAL)).get
        val latestPrivateUri = db.readOnlyMaster { implicit session => uriRepo.get(privateUri.id.get) }
        vanityUri.normalization === Some(Normalization.CANONICAL)
        vanityUri.url === "http://www.linkedin.com/in/viviensaulue"
        latestPrivateUri.redirect === Some(vanityUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
      }

      "find a variation with an upgraded normalization" in {
        val canonicalVariation = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("http://www.linkedin.com/in/viviensaulue").get }
        val httpsUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/in/viviensaulue")) }
        updateNormalizationNow(httpsUri, UntrustedCandidate("http://fr.linkedin.com/in/viviensaulue", Normalization.CANONICAL)).map(_.id) === Some(canonicalVariation.id)
        val latestHttpsUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.redirect === Some(canonicalVariation.id.get)
        latestHttpsUri.state === NormalizedURIStates.REDIRECTED
      }

      "shutdown shared actor system" in {
        system.shutdown()
        1 === 1
      }
    }
  }
}
