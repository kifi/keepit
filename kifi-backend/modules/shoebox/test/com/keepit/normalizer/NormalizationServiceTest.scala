package com.keepit.normalizer

import com.google.inject.Injector
import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.inject.FakeFortyTwoModule
import com.keepit.integrity.UriIntegrityPlugin
import com.keepit.model._
import com.keepit.rover.article.content.{ NormalizationInfo, LinkedInProfile, LinkedInProfileContent }
import com.keepit.rover.article.policy.ArticleFetchPolicy
import com.keepit.rover.article.{ DefaultArticle, ArticleKind, Article, LinkedInProfileArticle }
import com.keepit.rover.fetcher.FetchContext
import com.keepit.rover.{ FakeRoverServiceClientImpl, RoverServiceClient }
import com.keepit.rover.document.utils.Signature
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mutable.SpecificationLike
import com.keepit.common.time._

import scala.concurrent.Await
import scala.concurrent.duration._

class NormalizationServiceTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  def mkLinkedInArticle(url: String, id: Option[String]) =
    LinkedInProfileArticle(
      createdAt = currentDateTime,
      url = url,
      content = LinkedInProfileContent(
        destinationUrl = url,
        title = None,
        description = None,
        keywords = Seq.empty,
        authors = Seq.empty,
        openGraphType = None,
        publishedAt = None,
        profile = LinkedInProfile(
          id = id,
          title = "",
          overview = "",
          sections = ""
        ),
        http = FetchContext.ok(url),
        normalization = NormalizationInfo(
          canonicalUrl = Some(url),
          openGraphUrl = Some(url),
          alternateUrls = Set.empty,
          shortUrl = None
        )
      ))

  def fixLinkedInArticleForUrl(url: String, linkedInId: Option[String])(implicit injector: Injector): Unit = {
    val rover = inject[RoverServiceClient].asInstanceOf[FakeRoverServiceClientImpl]
    rover.setArticlesForUrl(url, Set(mkLinkedInArticle(url, linkedInId)))
    println(s"${rover.articlesByUrl}")
  }

  def fixSignatureForUrl(url: String, signature: String)(implicit injector: Injector): Unit = {
    inject[ArticleFetchPolicy].toBeScraped(url).foreach { expectedArticleKind =>
      val rover = inject[RoverServiceClient].asInstanceOf[FakeRoverServiceClientImpl]
      val SIZE = 1000 // signatures must have the same size to be comparable
      val fakeSignature = Signature(signature.getBytes.take(SIZE))
      rover.setSignatureForUrl(url, fakeSignature)(expectedArticleKind)
    }
  }

  def updateNormalizationNow(uri: NormalizedURI, candidates: NormalizationCandidate*)(implicit injector: Injector): Option[NormalizedURI] = {
    val uriIntegrityPlugin = inject[UriIntegrityPlugin]
    val seqAssigner = inject[ChangedURISeqAssigner]
    val id = Await.result(normalizationService.update(NormalizationReference(uri), candidates.toSet), 5 seconds)
    seqAssigner.assignSequenceNumbers()
    uriIntegrityPlugin.batchURIMigration()
    id.map { db.readOnlyMaster { implicit session => uriRepo.get(_) } }
  }

  val modules = Seq(
    FakeFortyTwoModule(),
    FakeDiscoveryModule(),
    FakeAirbrakeModule(),
    FakeActorSystemModule(),
    FakeElizaServiceClientModule(),
    FakeKeepImportsModule(),
    new ScalaModule {
      def configure() {
        bind[NormalizationService].to[NormalizationServiceImpl]
        bind[UrlPatternRuleRepo].to[UrlPatternRuleRepoImpl]
      }
    })

  "NormalizationService" should {

    "normalize a new http:// url to HTTP" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s => failedContentCheckRepo.createOrIncrease("abc", "xyz") }
        val httpUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://vimeo.com/48578814")) }
        httpUri.normalization === None
        updateNormalizationNow(httpUri)
        val latestHttpUri = db.readOnlyMaster { implicit session => uriRepo.get(httpUri.id.get) }
        latestHttpUri.normalization === Some(Normalization.HTTP)
      }
    }

    //todo(leo): fix test
    //    "does not normalize an http:// url to HTTP twice" in {
    //      withDb(modules: _*) { implicit injector =>
    //        db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://vimeo.com/48578814")) }
    //        val httpUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("http://vimeo.com/48578814") }.get
    //        httpUri.normalization === Some(Normalization.HTTP)
    //        updateNormalizationNow(httpUri, VerifiedCandidate("http://vimeo.com/48578814", Normalization.HTTP)) === None
    //      }
    //    }
    "redirect an existing http url to a new https:// url" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s => failedContentCheckRepo.createOrIncrease("abc", "xyz") }
        db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://vimeo.com/48578814")) }
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
    }
    "redirect a new http://www url to an existing https:// url" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s => failedContentCheckRepo.createOrIncrease("abc", "xyz") }
        db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
        val httpsUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get

        val httpWWWUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.vimeo.com/48578814")) }
        updateNormalizationNow(httpWWWUri)
        val latestHttpWWWUri = db.readOnlyMaster { implicit session => uriRepo.get(httpWWWUri.id.get) }
        latestHttpWWWUri.normalization === Some(Normalization.HTTPWWW)
        latestHttpWWWUri.redirect === Some(httpsUri.id.get)
        latestHttpWWWUri.state === NormalizedURIStates.REDIRECTED
        db.readOnlyMaster { implicit session => normalizedURIInterner.getByUri("http://www.vimeo.com/48578814") }.map(_.id) === Some(httpsUri.id)
      }
    }
    //todo(leo): fix test
    //    "upgrade an existing https:// url to a better normalization" in {
    //      withDb(modules: _*) { implicit injector =>
    //        db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
    //        val httpsUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
    //        httpsUri.normalization === Some(Normalization.HTTPS)
    //        updateNormalizationNow(httpsUri, VerifiedCandidate("https://vimeo.com/48578814", Normalization.CANONICAL))
    //        val latestHttpsUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsUri.id.get) }
    //        latestHttpsUri.normalization === Some(Normalization.CANONICAL)
    //      }
    //    }
    //    "redirect an existing canonical normalization to a most recent one" in {
    //      withDb(modules: _*) { implicit injector =>
    //        db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
    //        val canonicalUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
    //        canonicalUri.normalization === Some(Normalization.CANONICAL)
    //
    //        val moreRecentCanonicalUri = updateNormalizationNow(canonicalUri, VerifiedCandidate("http://vimeo.com/48578814", Normalization.CANONICAL)).get
    //        moreRecentCanonicalUri.state !== NormalizedURIStates.REDIRECTED
    //        moreRecentCanonicalUri.redirect === None
    //        moreRecentCanonicalUri.redirectTime === None
    //
    //        val redirectedCanonicalUri = db.readOnlyMaster { implicit session => uriRepo.get(canonicalUri.id.get) }
    //        redirectedCanonicalUri.redirect === Some(moreRecentCanonicalUri.id.get)
    //        redirectedCanonicalUri.state === NormalizedURIStates.REDIRECTED
    //      }
    //    }
    //    "ignore a random untrusted candidate" in {
    //      withDb(modules: _*) { implicit injector =>
    //        db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://vimeo.com/48578814")) }
    //        val httpsUri = db.readOnlyMaster { implicit session => uriRepo.getByNormalizedUrl("https://vimeo.com/48578814") }.get
    //        val newReference = updateNormalizationNow(httpsUri, UntrustedCandidate("http://www.iamrandom.com", Normalization.CANONICAL), UntrustedCandidate("http://www.iamsociallyrandom.com", Normalization.OPENGRAPH))
    //        newReference === None
    //      }
    //    }
    "not normalize a LinkedIn private profile to its public url if ids do not match" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s => failedContentCheckRepo.createOrIncrease("abc", "xyz") }
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/profile/view?id=17558679", normalization = Some(Normalization.HTTPSWWW))) }
        val httpPublicUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", normalization = Some(Normalization.HTTPWWW))) }
        fixLinkedInArticleForUrl(httpPublicUri.url, None)
        updateNormalizationNow(privateUri, UntrustedCandidate(httpPublicUri.url, Normalization.CANONICAL)) === None
      }
    }

    "normalize a LinkedIn private profile to its public url if ids match" in {
      withDb(modules: _*) { implicit injector =>
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/profile/view?id=17558679", normalization = Some(Normalization.HTTPSWWW))) }
        val httpsPublicUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", normalization = Some(Normalization.HTTPSWWW))) }
        val httpPublicUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", normalization = Some(Normalization.HTTPWWW))) }
        fixLinkedInArticleForUrl(httpPublicUri.url, Some("17558679"))
        val publicUri = updateNormalizationNow(privateUri, UntrustedCandidate(httpPublicUri.url, Normalization.CANONICAL)).get
        val latestPrivateUri = db.readOnlyMaster { implicit session => uriRepo.get(privateUri.id.get) }
        val latestHttpsPublicUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsPublicUri.id.get) }
        publicUri.normalization === Some(Normalization.CANONICAL)
        latestPrivateUri.redirect === Some(publicUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
        latestHttpsPublicUri.redirect === Some(publicUri.id.get)
        latestHttpsPublicUri.state === NormalizedURIStates.REDIRECTED
      }
    }

    "normalize a LinkedIn public profile to a vanity public url if this url is trusted" in {
      withDb(modules: _*) { implicit injector =>
        val publicUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.linkedin.com/pub/leo\u002dgrimaldi/12/42/2b3", normalization = Some(Normalization.HTTPWWW))) }
        val vanityUrl = "http://www.linkedin.com/in/leo"
        fixSignatureForUrl(publicUri.url, "fake signature")
        fixSignatureForUrl(vanityUrl, "fake signature")

        updateNormalizationNow(publicUri, UntrustedCandidate(vanityUrl, Normalization.CANONICAL)) === None

        db.readWrite { implicit session => urlPatternRuleRepo.save(UrlPatternRule(pattern = LinkedInNormalizer.linkedInCanonicalPublicProfile.toString(), trustedDomain = Some("""^https?://([a-z]{2,3})\.linkedin\.com/.*"""))) }
        val vanityUri = updateNormalizationNow(publicUri, UntrustedCandidate(vanityUrl, Normalization.CANONICAL)).get
        val latestPublicUri = db.readOnlyMaster { implicit session => uriRepo.get(publicUri.id.get) }
        vanityUri.normalization === Some(Normalization.CANONICAL)
        vanityUri.url === "http://www.linkedin.com/in/leo"
        latestPublicUri.redirect === Some(vanityUri.id.get)
        latestPublicUri.state === NormalizedURIStates.REDIRECTED
      }
    }

    "normalize a French LinkedIn private profile to a vanity public url" in {
      withDb(modules: _*) { implicit injector =>
        val privateUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://fr.linkedin.com/profile/view?id=136123062")) }
        val vanityUrl = "http://www.linkedin.com/in/viviensaulue"
        fixLinkedInArticleForUrl(vanityUrl, Some("136123062"))

        val frenchVanityUrl = "http://fr.linkedin.com/in/viviensaulue" // fr.linkedin.com is expected to be statically normalized to www.linkedin.com
        val vanityUri = updateNormalizationNow(privateUri, UntrustedCandidate(frenchVanityUrl, Normalization.CANONICAL)).get

        val latestPrivateUri = db.readOnlyMaster { implicit session => uriRepo.get(privateUri.id.get) }
        vanityUri.normalization === Some(Normalization.CANONICAL)
        vanityUri.url === vanityUrl
        latestPrivateUri.redirect === Some(vanityUri.id.get)
        latestPrivateUri.state === NormalizedURIStates.REDIRECTED
      }
    }

    "find a variation with an upgraded normalization" in {
      withDb(modules: _*) { implicit injector =>
        val canonicalVariation = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("http://www.linkedin.com/in/viviensaulue").withNormalization(Normalization.CANONICAL)) }
        val httpsUri = db.readWrite { implicit session => uriRepo.save(NormalizedURI.withHash("https://www.linkedin.com/in/viviensaulue")) }
        val vanityUri = updateNormalizationNow(httpsUri, UntrustedCandidate("http://fr.linkedin.com/in/viviensaulue", Normalization.CANONICAL)).get
        vanityUri.id === canonicalVariation.id
        val latestHttpsUri = db.readOnlyMaster { implicit session => uriRepo.get(httpsUri.id.get) }
        latestHttpsUri.redirect === Some(canonicalVariation.id.get)
        latestHttpsUri.state === NormalizedURIStates.REDIRECTED
      }
    }
  }

}
