package com.keepit.scraper

import com.google.inject._
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.scraper.extractor._
import com.keepit.search.{LangDetector, Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import java.io.File
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import org.joda.time.{ReadableDuration, DateTime, Days}
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.routing.SmallestMailboxRouter
import com.keepit.common.performance.timing
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import scala.util.Success
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory
import org.joda.time.format.DateTimeFormatter
import scala.ref.WeakReference
import play.api.Play
import play.api.Play.current
import com.keepit.common.db.Id


@ProvidedBy(classOf[ScrapeProcessorProvider])
trait ScrapeProcessor {
  def fetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]):Future[Option[BasicArticle]]
  def scrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]):Future[(NormalizedURI, Option[Article])]
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]): Unit
}

@Singleton
class ScrapeProcessorProvider @Inject() (
  scraperConfig: ScraperConfig,
  queuedScrapeProcessor:QueuedScrapeProcessor,
  asyncScrapeProcessor:AsyncScrapeProcessor,
  syncScrapeProcessor:SyncScrapeProcessor
) extends Provider[ScrapeProcessor] with Logging {

  lazy val processor = if (scraperConfig.queued) queuedScrapeProcessor else if (scraperConfig.async) asyncScrapeProcessor else syncScrapeProcessor // config-based toggle
  log.info(s"[ScrapeProcessorProvider] created with config:$scraperConfig proc:$processor")

  def get = processor
}

class SyncScrapeProcessor @Inject() (config:ScraperConfig, sysProvider:Provider[ActorSystem], procProvider:Provider[SyncScraperActor], nrOfInstances:Int) extends ScrapeProcessor {

  lazy val system = sysProvider.get
  lazy val actor = system.actorOf(Props(procProvider.get).withRouter(SmallestMailboxRouter(nrOfInstances)))

  implicit val timeout = Timeout(config.actorTimeout)

  def fetchBasicArticle(url: String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    (actor ? FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt)).mapTo[Option[BasicArticle]]
  }

  def scrapeArticle(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    (actor ? ScrapeArticle(uri, info, proxyOpt)).mapTo[(NormalizedURI, Option[Article])]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Unit = {
    actor ! AsyncScrape(uri, info, proxyOpt)
  }
}

case class AsyncScrape(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy])
case class FetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType])
case class ScrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy])

class SyncScraperActor @Inject() (
  airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  syncScraper: SyncScraper,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks
) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[ScraperProcessor-actor] created $this")

  implicit val myConfig = config

  def receive = {

    case FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt) => {
      log.info(s"[FetchArticle] message received; url=$url")
      val ts = System.currentTimeMillis
      val extractor = extractorProviderTypeOpt match {
        case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
        case _ => extractorFactory(url)
      }
      val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
      val res = fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) => Some(syncScraper.basicArticle(url, extractor))
        case _ => None
      }
      log.info(s"[FetchArticle] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$res")
      sender ! res
    }

    case ScrapeArticle(uri, info, proxyOpt) => {
      log.info(s"[ScrapeArticle] message received; url=${uri.url}")
      val ts = System.currentTimeMillis
      val res = syncScraper.safeProcessURI(uri, info, proxyOpt)
      log.info(s"[ScrapeArticle] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1.state}")
      sender ! res
    }
    case AsyncScrape(nuri, info, proxyOpt) => timing(s"AsyncScrape: uri=(${nuri.id}, ${nuri.url}) info=(${info.id},${info.destinationUrl}) proxy=$proxyOpt") {
      log.info(s"[AsyncScrape] message received; url=${nuri.url}")
      val ts = System.currentTimeMillis
      val (uri, a) = syncScraper.safeProcessURI(nuri, info, proxyOpt)
      log.info(s"[AsyncScrape] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=(${uri.id}, ${uri.state})")
    }
  }

}


import java.util.concurrent.{Future => JFuture, Callable => JCallable}

abstract class ScrapeCallable(val submitTS:Long, val callTS:AtomicLong, val uri:NormalizedURI, val info:ScrapeInfo, val proxyOpt:Option[HttpProxy]) extends Callable[(NormalizedURI, Option[Article])] {
  lazy val submitLocalTime = new DateTime(submitTS).toLocalTime
  def callLocalTime = new DateTime(callTS.get).toLocalTime
  def runMillis(curr:Long) = curr - callTS.get
  def runDuration(curr:Long) = Duration(runMillis(curr), TimeUnit.MILLISECONDS)
  override def toString = s"[Task:([$submitLocalTime],[${callLocalTime}],${uri.id.getOrElse("")},${info.id.getOrElse("")},${uri.url})]"
}

@Singleton
class QueuedScrapeProcessor @Inject() (
  airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks
) extends ScrapeProcessor with Logging {

  val LONG_RUNNING_THRESHOLD = if (Play.isDev) 200 else sys.props.get("scraper.long.threshold") map (_.toInt) getOrElse (10 * 1000 * 60)

  val pSize = Runtime.getRuntime.availableProcessors * 1024
  val fjPool = new ForkJoinPool(pSize)
  val submittedQ = new ConcurrentLinkedQueue[WeakReference[(ScrapeCallable, ForkJoinTask[(NormalizedURI, Option[Article])])]]()

  log.info(s"[QScraper.ctr] nrInstances=$pSize, pool=$fjPool")

  val terminator = new Runnable {
    def run(): Unit = {
      try {
        log.info(s"[terminator] checking for long-running tasks to kill ${submittedQ.size} ${fjPool.getQueuedSubmissionCount} ${fjPool.getQueuedTaskCount} ...")
        if (submittedQ.isEmpty) { // some low threshold
          // check fjPool; do something useful
        } else {
          val iter = submittedQ.iterator
          while (iter.hasNext) {
            val curr = System.currentTimeMillis
            val ref = iter.next
            ref.get map { case (sc, fjTask) =>
              if (sc.callTS.get == 0) {
                log.info(s"[terminator] $sc has not yet started")
              } else {
                log.info(s"[terminator] $sc has been running for ${curr - sc.callTS.get}ms")
                if (curr - sc.callTS.get > LONG_RUNNING_THRESHOLD * 3) { // tweak this
                  val msg = s"[terminator] kill LONG (${Duration(curr - sc.callTS.get, TimeUnit.MILLISECONDS)}) running task: $sc; ${fjTask}"
                  airbrake.notify(msg)
                  log.warn(msg)
                  fjTask.cancel(true) // wire up HttpGet.abort
                }
              }
            } orElse {
              log.info(s"[terminator] remove collected entry $ref from queue")
              try {
                iter.remove()
              } catch {
                case t:Throwable =>
                  log.error(s"[terminator] Caught exception $t; (cause=${t.getCause}) while attempting to remove collected entry $ref from queue")
              }
              None
            }
          }
        }
      } catch {
        case t:Throwable =>
          log.error(s"[terminator] Caught exception $t; (cause=${t.getCause}); (stack=${t.getStackTraceString}")
      }
    }
  }

  val scheduler = Executors.newSingleThreadScheduledExecutor
  scheduler.scheduleWithFixedDelay(terminator, config.scrapePendingFrequency, config.scrapePendingFrequency * 2, TimeUnit.SECONDS)

  private def worker = new SyncScraper(airbrake, config, httpFetcher, extractorFactory, articleStore, s3ScreenshotStore, helper)
  def asyncScrape(nuri: NormalizedURI, scrapeInfo: ScrapeInfo, proxy: Option[HttpProxy]): Unit = {
    log.info(s"[QScraper.asyncScrape($fjPool)] uri=$nuri info=$scrapeInfo proxy=$proxy")
    try {
      val callable = new ScrapeCallable(System.currentTimeMillis, new AtomicLong, nuri, scrapeInfo, proxy) {
        def call(): (NormalizedURI, Option[Article]) = {
          val name = Thread.currentThread.getName
          callTS.set(System.currentTimeMillis)
          Thread.currentThread().setName(s"$name##${toString}")
          try {
            val res = timing(s"QScraper.safeProcessURI(${uri.id}),${uri.url}") {
              worker.safeProcessURI(uri, info, proxyOpt)
            }
            val ts = System.currentTimeMillis
            if (runMillis(ts) > LONG_RUNNING_THRESHOLD)
              log.warn(s"[QScraper] LONG (${runDuration(ts)}) running scraping task:${toString}; ${fjPool}")
            res
          } finally {
            Thread.currentThread().setName(name)
          }
        }
      }
      val fjTask:ForkJoinTask[(NormalizedURI, Option[Article])] = fjPool.submit(callable)
      if (!fjTask.isDone) {
        submittedQ.offer(WeakReference(callable, fjTask)) // should never return false
      }
    } catch {
      case t:Throwable =>
        log.info(s"Caught exception ${t.getMessage}; cause=${t.getCause}; QPS.asyncScrape($fjPool): uri=$nuri info=$scrapeInfo")
        airbrake.notify(t)
    }
  }

  def scrapeArticle(uri: NormalizedURI, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    log.info(s"[ScrapeArticle] message received; url=${uri.url}")
    val ts = System.currentTimeMillis
    val res = worker.safeProcessURI(uri, info, proxyOpt)
    log.info(s"[ScrapeArticle] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1.state}")
    Future.successful(res)
  }

  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    log.info(s"[FetchArticle] message received; url=$url")
    val ts = System.currentTimeMillis
    val extractor = extractorProviderTypeOpt match {
      case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
      case _ => extractorFactory(url)
    }
    val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
    val res = fetchStatus.statusCode match {
      case HttpStatus.SC_OK if !(helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) => Some(worker.basicArticle(url, extractor))
      case _ => None
    }
    log.info(s"[FetchArticle] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$res")
    Future.successful(res)
  }
}

// straight port from original (local) code
class SyncScraper @Inject() (
  airbrake: AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks
) extends Logging {
  
  implicit val myConfig = config
  val awaitTTL = (myConfig.syncAwaitTimeout seconds)

  private[scraper] def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): (NormalizedURI, Option[Article]) = try {
    processURI(uri, info, proxyOpt)
  } catch {
    case t: Throwable => {
      log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
      airbrake.notify(t)
      val latestUriOpt = helper.syncGetNormalizedUri(uri)
      // update the uri state to SCRAPE_FAILED
      val savedUriOpt = for (latestUri <- latestUriOpt) yield {
        if (latestUri.state == NormalizedURIStates.INACTIVE) latestUri else {
          helper.syncSaveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
      }
      // then update the scrape schedule
      val savedInfoF = helper.syncSaveScrapeInfo(info.withFailure())
      (savedUriOpt.getOrElse(uri), None)
    }
  }

  private def processURI(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): (NormalizedURI, Option[Article]) = {
    log.info(s"[processURI] scraping ${uri.url} $info")
    val fetchedArticle = fetchArticle(uri, info, proxyOpt)
    val latestUri = helper.syncGetNormalizedUri(uri).get
    if (latestUri.state == NormalizedURIStates.INACTIVE) (latestUri, None)
    else fetchedArticle match {
      case Scraped(article, signature, redirects) =>
        val updatedUri = processRedirects(latestUri, redirects)

        // check if document is not changed or does not need to be reindexed
        if (latestUri.title == Option(article.title) && // title change should always invoke indexing
          latestUri.restriction == updatedUri.restriction && // restriction change always invoke indexing
          latestUri.state != NormalizedURIStates.SCRAPE_WANTED &&
          latestUri.state != NormalizedURIStates.SCRAPE_FAILED &&
          signature.similarTo(Signature(info.signature)) >= (1.0d - config.changeThreshold * (config.minInterval / info.interval))
        ) {
          // the article does not need to be reindexed update the scrape schedule, uri is not changed
          helper.syncSaveScrapeInfo(info.withDocumentUnchanged())
          log.info(s"[processURI] (${uri.url}) no change detected")
          (latestUri, None)
        } else {
          // the article needs to be reindexed

          // store a scraped article in a store map
          articleStore += (latestUri.id.get -> article)

          // first update the uri state to SCRAPED
          val scrapedURI = helper.syncSaveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))

          // then update the scrape schedule
          helper.syncSaveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
          helper.syncGetBookmarksByUriWithoutTitle(scrapedURI.id.get).foreach { bookmark =>
            helper.syncSaveBookmark(bookmark.copy(title = scrapedURI.title))
          }
          log.info(s"[processURI] fetched uri ${scrapedURI.url} => article(${article.id}, ${article.title})")

          def shouldUpdateScreenshot(uri: NormalizedURI) = {
            uri.screenshotUpdatedAt map { update =>
              Days.daysBetween(currentDateTime.toDateMidnight, update.toDateMidnight).getDays() >= 5
            } getOrElse true
          }
          if(shouldUpdateScreenshot(scrapedURI)) s3ScreenshotStore.updatePicture(scrapedURI)

          log.info(s"[processURI] (${uri.url}) needs re-indexing; scrapedURI=(${scrapedURI.id}, ${scrapedURI.state}, ${scrapedURI.url})")
          (scrapedURI, Some(article))
        }
      case NotScrapable(destinationUrl, redirects) =>
        val unscrapableURI = {
          helper.syncSaveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
          val toBeSaved = processRedirects(latestUri, redirects).withState(NormalizedURIStates.UNSCRAPABLE)
          helper.syncSaveNormalizedUri(toBeSaved)
        }
        log.info(s"[processURI] (${uri.url}) not scrapable; unscrapableURI=(${unscrapableURI.id}, ${unscrapableURI.state}, ${unscrapableURI.url}})")
        (unscrapableURI, None)
      case com.keepit.scraper.NotModified =>
        // update the scrape schedule, uri is not changed
        helper.syncSaveScrapeInfo(info.withDocumentUnchanged())
        log.info(s"[processURI] (${uri.url} not modified; latestUri=(${latestUri.id}, ${latestUri.state}, ${latestUri.url}})")
        (latestUri, None)
      case Error(httpStatus, msg) =>
        // store a fallback article in a store map
        val article = Article(
          id = latestUri.id.get,
          title = latestUri.title.getOrElse(""),
          description = None,
          keywords = None,
          media = None,
          content = "",
          scrapedAt = currentDateTime,
          httpContentType = None,
          httpOriginalContentCharset = None,
          state = NormalizedURIStates.SCRAPE_FAILED,
          message = Option(msg),
          titleLang = None,
          contentLang = None,
          destinationUrl = None)
        articleStore += (latestUri.id.get -> article)
        // the article is saved. update the scrape schedule and the state to SCRAPE_FAILED and save
        val errorURI = {
          helper.syncSaveScrapeInfo(info.withFailure())
          helper.syncSaveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
        log.warn(s"[processURI] Error($httpStatus, $msg); errorURI=(${errorURI.id}, ${errorURI.state}, ${errorURI.url})")
        (errorURI, None)
    }
  }

  def fetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): ScraperResult = {
    try {
      URI.parse(normalizedUri.url) match {
        case Success(uri) =>
          uri.scheme match {
            case Some("file") => Error(-1, "forbidden scheme: %s".format("file"))
            case _ => fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
          }
        case _ => fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
      }
    } catch {
      case t: Throwable => {
        log.error(s"[fetchArticle] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
        fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
      }
    }
  }

  private def getIfModifiedSince(uri: NormalizedURI, info: ScrapeInfo) = {
    if (uri.state == NormalizedURIStates.SCRAPED) {
      info.signature match {
        case "" => None // no signature. this is the first time
        case _ => Some(info.lastScrape)
      }
    } else {
      None
    }
  }

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): ScraperResult = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.info(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince, proxy = proxyOpt){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) {
            NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects)
          } else {
            val content = extractor.getContent
            val title = getTitle(extractor)
            val description = getDescription(extractor)
            val keywords = getKeywords(extractor)
            val media = getMediaTypeString(extractor)
            val signature = Signature(Seq(title, description.getOrElse(""), keywords.getOrElse(""), content))

            val contentLang = description match {
              case Some(desc) => LangDetector.detect(content + " " + desc)
              case None => LangDetector.detect(content)
            }
            val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language

            val article: Article = Article(
              id = normalizedUri.id.get,
              title = title,
              description = description,
              keywords = keywords,
              media = media,
              content = content,
              scrapedAt = currentDateTime,
              httpContentType = extractor.getMetadata("Content-Type"),
              httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
              state = NormalizedURIStates.SCRAPED,
              message = None,
              titleLang = Some(titleLang),
              contentLang = Some(contentLang),
              destinationUrl = fetchStatus.destinationUrl)
            val res = Scraped(article, signature, fetchStatus.redirects)
            log.info(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
            res
          }
        case HttpStatus.SC_NOT_MODIFIED =>
          com.keepit.scraper.NotModified
        case _ =>
          Error(fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed"))
      }
    } catch {
      case e: Throwable => {
        log.error(s"[fetchArticle] fetch failed ${normalizedUri.url} $info $httpFetcher;\nException: $e; Cause: ${e.getCause};\nStack trace:\n${e.getStackTrace.mkString(File.separator)}")
        Error(-1, "fetch failed: %s".format(e.toString))
      }
    }
  }

  private[this] def getTitle(x: Extractor): String = x.getMetadata("title").getOrElse("")

  private[this] def getDescription(x: Extractor): Option[String] = x.getMetadata("description")

  private[this] def getKeywords(x: Extractor): Option[String] = x.getKeywords

  private[this] def getMediaTypeString(x: Extractor): Option[String] = MediaTypes(x).getMediaTypeString(x)

  def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
    title = getTitle(extractor),
    content = extractor.getContent,
    description = getDescription(extractor),
    media = getMediaTypeString(extractor),
    httpContentType = extractor.getMetadata("Content-Type"),
    httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
    destinationUrl = Some(destinationUrl)
  )

  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): NormalizedURI = {
    redirects.find(_.isLocatedAt(uri.url)) match {
      case Some(redirect) if !redirect.isPermanent || hasFishy301(uri) => updateRedirectRestriction(uri, redirect)
      case Some(permanentRedirect) if permanentRedirect.isAbsolute => helper.syncRecordPermanentRedirect(removeRedirectRestriction(uri), permanentRedirect)
      case _ => removeRedirectRestriction(uri)
    }
  }

  private def removeRedirectRestriction(uri: NormalizedURI): NormalizedURI = uri.restriction match {
    case Some(restriction) if Restriction.redirects.contains(restriction) => uri.copy(restriction = None)
    case _ => uri
  }

  private def updateRedirectRestriction(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = {
    val restriction = Restriction.http(redirect.statusCode)
    if (Restriction.redirects.contains(restriction)) uri.copy(restriction = Some(restriction)) else removeRedirectRestriction(uri)
  }

  private def hasFishy301(movedUri: NormalizedURI): Boolean = {
    val hasFishy301Restriction = movedUri.restriction == Some(Restriction.http(301))
    val wasKeptRecently = helper.syncGetLatestBookmark(movedUri.id.get).map(_.updatedAt.isAfter(currentDateTime.minusHours(1))).getOrElse(false)
    hasFishy301Restriction || wasKeptRecently
    hasFishy301Restriction
  }

}

