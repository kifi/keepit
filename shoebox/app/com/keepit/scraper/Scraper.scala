package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.db.{Id, CX}
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model._
import com.keepit.scraper.extractor.DefaultExtractor
import com.keepit.scraper.extractor.DefaultExtractorFactory
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.YoutubeExtractorFactory
import com.keepit.search.LangDetector
import com.google.inject.Inject
import org.apache.http.HttpStatus
import org.joda.time.Seconds
import play.api.Play.current

object Scraper {
  val BATCH_SIZE = 100

  val maxContentChars = 100000 // 100K chars
}

class Scraper @Inject() (articleStore: ArticleStore, scraperConfig: ScraperConfig) extends Logging {

  implicit val config = scraperConfig
  val httpFetcher = new HttpFetcher

  def run(): Seq[(NormalizedURI, Option[Article])] = {
    val startedTime = currentDateTime
    log.info("starting a new scrape round")
    val tasks = CX.withConnection { implicit c =>
      ScrapeInfoCxRepo.getOverdueList().map{ info => (NormalizedURICxRepo.get(info.uriId), info) }
    }
    log.info("got %s uris to scrape".format(tasks.length))
    val scrapedArticles = tasks.map{ case (uri, info) => safeProcessURI(uri, info) }
    val jobTime = Seconds.secondsBetween(startedTime, currentDateTime).getSeconds()
    log.info("succesfuly scraped %s articles out of %s in %s seconds:\n%s".format(
        scrapedArticles.flatMap{ a => a._2 }.size, tasks.size, jobTime, scrapedArticles map {a => a._1} mkString "\n"))
    scrapedArticles
  }

  def safeProcessURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = try {
    val info = CX.withConnection { implicit c => ScrapeInfoCxRepo.ofUri(uri) }
    safeProcessURI(uri, info)
  }

  private def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = try {
      processURI(uri, info)
    } catch {
      case e =>
        log.error("uncaught exception while scraping uri %s".format(uri), e)
        val errorURI = CX.withConnection { implicit c =>
          info.withFailure().save
          uri.withState(NormalizedURIStates.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }

  private val unscrapables = Seq("//www.facebook.com/login", "//accounts.google.com/ServiceLogin", "//www.google.com/accounts/ServiceLogin", "//app.asana.com/")
  private def isUnscrapable(url: String): Boolean = !unscrapables.forall(x => !url.contains(x))

  private def processURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = {
    log.info("scraping %s".format(uri))
    fetchArticle(uri) match {
      case Left(article) =>
        // store a scraped article in a store map
        articleStore += (uri.id.get -> article)
        // the article is saved, now detect the document change. making the detection more strict as time goes by.
        val oldSig = Signature(info.signature)
        val newSig = computeSignature(article)
        val docChanged = (newSig.similarTo(oldSig) < (1.0d - config.changeThreshold * (config.minInterval / info.interval)))

        val scrapedURI = CX.withConnection { implicit c =>

          val isUnscrape = if (isUnscrapable(uri.url) || (article.destinationUrl.isDefined && isUnscrapable(article.destinationUrl.get))) true else false

          if (docChanged) {
            // update the scrape schedule and the uri state to SCRAPED
            info.withDestinationUrl(article.destinationUrl).withDocumentChanged(newSig.toBase64).save
            if (isUnscrape)
              uri.withState(NormalizedURIStates.UNSCRAPABLE).save
            else
              uri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED).save
          } else {
            // update the scrape schedule, uri is not changed
            info.withDestinationUrl(article.destinationUrl).withDocumentUnchanged().save
            if (isUnscrape)
              uri.withState(NormalizedURIStates.UNSCRAPABLE).save
            else
              uri
          }
        }
        log.info("fetched uri %s => %s".format(uri, article))
        (scrapedURI, Some(article))
      case Right(error) =>
        // store a fallback article in a store map
        val article = Article(
            id = uri.id.get,
            title = uri.title.getOrElse(""),
            content = "",
            scrapedAt = currentDateTime,
            httpContentType = None,
            httpOriginalContentCharset = None,
            state = NormalizedURIStates.SCRAPE_FAILED,
            message = Option(error.msg),
            titleLang = None,
            contentLang = None,
            destinationUrl = None)
        articleStore += (uri.id.get -> article)
        // the article is saved. update the scrape schedule and the state to SCRAPE_FAILED and save
        val errorURI = CX.withConnection { implicit c =>
          info.withFailure().save
          uri.withState(NormalizedURIStates.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  }

  private def getExtractor(url: String): Extractor = {
    try {
      URI.parse(url) match {
        case Some(uri) =>
          Extractor.factories.find(_.isDefinedAt(uri)).map{ f =>
            f.apply(uri)
          }.getOrElse(throw new Exception("failed to find a extractor factory"))
        case None =>
          log.warn("uri parsing failed: [%s]".format(url))
          new DefaultExtractor(url, Scraper.maxContentChars)
      }
    } catch {
      case e =>
          log.warn("uri parsing failed: [%s][%s]".format(url, e.toString))
          new DefaultExtractor(url, Scraper.maxContentChars)
    }
  }

  def fetchArticle(normalizedUri: NormalizedURI): Either[Article, ScraperError] = {
    try {
      URI.parse(normalizedUri.url) match {
        case Some(uri) =>
          uri.scheme match {
            case Some("file") => Right(ScraperError(normalizedUri, -1, "forbidden scheme: %s".format("file")))
            case _ => fetchArticle(normalizedUri, httpFetcher)
          }
        case _ => fetchArticle(normalizedUri, httpFetcher)
      }
    } catch {
      case _ => fetchArticle(normalizedUri, httpFetcher)
    }
  }

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher): Either[Article, ScraperError] = {
    val url = normalizedUri.url
    val extractor = getExtractor(url)

    try {
      val fetchStatus = httpFetcher.fetch(url){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          val content = extractor.getContent
          val contentLang = LangDetector.detect(content)
          val title = extractor.getMetadata("title").getOrElse("")
          val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language
          val destinationUrl = fetchStatus.destinationUrl
          Left(Article(id = normalizedUri.id.get,
                       title = title,
                       content = content,
                       scrapedAt = currentDateTime,
                       httpContentType = extractor.getMetadata("Content-Type"),
                       httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
                       state = NormalizedURIStates.SCRAPED,
                       message = None,
                       titleLang = Some(titleLang),
                       contentLang = Some(contentLang),
                       destinationUrl = destinationUrl))
        case _ =>
          Right(ScraperError(normalizedUri, fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed")))
      }
    } catch {
      case e => Right(ScraperError(normalizedUri, -1, "fetch failed: %s".format(e.toString)))
    }
  }

  private[this] def computeSignature(article: Article) = new SignatureBuilder().add(article.title).add(article.content).build

  def close() {
    httpFetcher.close()
  }
}

