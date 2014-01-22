package com.keepit.scraper

import java.util.concurrent.atomic.AtomicLong
import com.keepit.model.{HttpProxy, ScrapeInfo, NormalizedURI}
import java.util.concurrent._
import com.keepit.search.{ArticleStore, Article}
import org.joda.time.DateTime
import scala.concurrent.duration.Duration
import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.extractor.{LinkedInIdExtractor, ExtractorProviderTypes, ExtractorProviderType, ExtractorFactory}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.logging.Logging
import play.api.Play
import scala.ref.WeakReference
import com.keepit.common.performance._
import scala.Some
import scala.concurrent.Future
import org.apache.http.HttpStatus
import play.api.Play.current


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
  helper: SyncShoeboxDbCallbacks) extends ScrapeProcessor with Logging {

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