package com.keepit.scraper

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference, AtomicLong}
import com.keepit.model._
import java.util.concurrent.{ForkJoinPool, ForkJoinTask, ConcurrentLinkedQueue, Callable, Executors, TimeUnit}
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
import org.apache.http.HttpStatus
import play.api.Play.current
import scala.concurrent._
import play.api.libs.json.Json
import com.keepit.common.zookeeper.ServiceDiscovery
import scala.util.{Try, Success, Failure}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

abstract class TracedCallable[T](val name:String, val submitTS:Long) extends Callable[Try[T]] {

  def doWork:T

  val callTS = new AtomicLong
  val isDone = new AtomicBoolean
  val threadRef = new AtomicReference[Thread]
  val killCount = new AtomicInteger

  val trRef = new AtomicReference[Try[T]]

  lazy val submitDateTime = new DateTime(submitTS)
  def callDateTime = new DateTime(callTS.get)

  def runMillis(curr:Long) = curr - callTS.get
  def runDuration(curr:Long) = Duration(runMillis(curr), TimeUnit.MILLISECONDS)

  def call(): Try[T] = {
    val name = Thread.currentThread.getName
    callTS.set(System.currentTimeMillis)
    Thread.currentThread().setName(s"$name##${toString}")
    threadRef.set(Thread.currentThread())
    try {
      val r = Try(doWork)
      trRef.set(r)
      r
    } finally {
      threadRef.set(null)
      Thread.currentThread().setName(name)
    }
  }

  override def toString = s"[TracedCallable($name)] $submitDateTime ${callDateTime}"
}

object TracedCallable {
  implicit class AtomicRef[T](val underlying:AtomicReference[T]) extends AnyVal {
    def asOpt[T] = {
      val r = underlying.get
      if (r == null) None else Some(r)
    }
  }
}

abstract class ScrapeCallable(submitTS:Long, val uri:NormalizedURI, val info:ScrapeInfo, val proxyOpt:Option[HttpProxy])
  extends TracedCallable[(NormalizedURI, Option[Article])]("Scrape", submitTS) {
  override def toString = {
    val taskDetails = ScraperTaskDetails(uri.id, info.id, uri.url, submitDateTime, callDateTime, killCount.get)
    s"[Scrape:${Json.stringify(Json.toJson(taskDetails))}]"
  }
}

/*
abstract class ScrapeCallable(val submitTS:Long, val callTS:AtomicLong, val uri:NormalizedURI, val info:ScrapeInfo, val proxyOpt:Option[HttpProxy]) extends Callable[(NormalizedURI, Option[Article])] {
  val killCount = new AtomicInteger()
  val threadRef = new AtomicReference[Thread]()
  val exRef = new AtomicReference[Throwable]()
  lazy val submitDateTime = new DateTime(submitTS)
  lazy val submitLocalTime = submitDateTime.toLocalTime
  def callDateTime = new DateTime(callTS.get)
  def callLocalTime = callDateTime.toLocalTime
  def runMillis(curr:Long) = curr - callTS.get
  def runDuration(curr:Long) = Duration(runMillis(curr), TimeUnit.MILLISECONDS)
  override def toString = {
    val taskDetails = ScraperTaskDetails(uri.id, info.id, uri.url, submitDateTime, callDateTime, killCount.get)
    s"[Scrape:${Json.stringify(Json.toJson(taskDetails))}]"
  }
}
*/

@Singleton
class QueuedScrapeProcessor @Inject() (
  val airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  serviceDiscovery: ServiceDiscovery,
  asyncHelper: ShoeboxDbCallbacks,
  helper: SyncShoeboxDbCallbacks) extends ScrapeProcessor with Logging with ScraperUtils {

  val LONG_RUNNING_THRESHOLD = if (Play.isDev) 200 else sys.props.get("scraper.terminate.threshold") map (_.toInt) getOrElse (5 * 1000 * 60) // adjust as needed
  val Q_SIZE_THRESHOLD = sys.props.get("scraper.queue.size.threshold") map (_.toInt) getOrElse (100)
  val pSize = Runtime.getRuntime.availableProcessors * 1024
  val fjPool = new ForkJoinPool(pSize) // some niceties afforded by this class, but could ditch it if need be
  val submittedQ = new ConcurrentLinkedQueue[WeakReference[(ScrapeCallable, ForkJoinTask[Try[(NormalizedURI, Option[Article])]])]]()

  log.info(s"[QScraper.ctr] nrInstances=$pSize, pool=$fjPool")

  private def removeRef(iter:java.util.Iterator[_], msgOpt:Option[String] = None) {
    try {
      for (msg <- msgOpt)
        log.info(msg)
      iter.remove()
    } catch {
      case t:Throwable =>
        log.error(s"[terminator] Caught exception $t; (cause=${t.getCause}) while attempting to remove entry from queue")
    }
  }

  private def doNotScrape(sc:ScrapeCallable, msgOpt:Option[String])(implicit scraperConfig:ScraperConfig) { // can be removed once things are settled
    try {
      for (msg <- msgOpt)
        log.info(msg)
      helper.syncSaveNormalizedUri(sc.uri.withState(NormalizedURIStates.SCRAPE_FAILED)) // todo: UNSCRAPABLE where appropriate
      helper.syncSaveScrapeInfo(sc.info.withFailure) // todo: mark INACTIVE where appropriate
    } catch {
      case t:Throwable => logErr(t, "terminator", s"deactivate $sc")
    }
  }

  import TracedCallable._
  val terminator = new Runnable {
    def run(): Unit = {
      try {
        log.info(s"[terminator] checking for long-running tasks to kill q.size=${submittedQ.size} fj(#submit)=${fjPool.getQueuedSubmissionCount} fj(#task)=${fjPool.getQueuedTaskCount} ...")
        if (submittedQ.isEmpty) { // some low threshold
          // check fjPool; do something useful
        } else {
          val iter = submittedQ.iterator
          while (iter.hasNext) {
            val curr = System.currentTimeMillis
            val ref = iter.next
            ref.get map { case (sc, fjTask) =>
              if (sc.callTS.get == 0) log.info(s"[terminator] $sc has not yet started")
              else if (fjTask.isDone) removeRef(iter, Some(s"[terminator] $sc isDone=true; remove from q"))
              else if (sc.trRef.asOpt.isDefined && sc.trRef.get.isFailure) removeRef(iter, Some(s"[terminator] $sc isFailure=true; ${sc.trRef.get}; remove from q"))
              // else if (sc.exRef.get != null) removeRef(iter, Some(s"[terminator] $sc caught error ${sc.exRef.get}; remove from q"))
              else {
                val runMillis = curr - sc.callTS.get
                if (runMillis > LONG_RUNNING_THRESHOLD * 2) {
                  log.error(s"[terminator] attempt# ${sc.killCount.get} to kill LONG ($runMillis ms) running task: $sc; stackTrace=${sc.threadRef.get.getStackTrace.mkString("|")}")
                  fjTask.cancel(true)
                  val killCount = sc.killCount.incrementAndGet()
                  doNotScrape(sc, Some(s"[terminator] deactivate LONG ($runMillis ms) running task: $sc"))(config)
                  if (fjTask.isDone) removeRef(iter, Some(s"[terminator] $sc is terminated; remove from q"))
                  else {
                    sc.threadRef.get.interrupt
                    if (sc.threadRef.get.isInterrupted) {
                      log.info(s"[terminator] thread ${sc.threadRef.get} is now interrupted")
                      // safeRemove -- later
                    } else { // may consider hard-stop
                      val msg = s"[terminator] ($killCount) attempts failed to terminate LONG ($runMillis ms) running task: $sc; $fjTask; stackTrace=${sc.threadRef.get.getStackTrace.mkString("|")}"
                      log.error(msg)
                      if (killCount % 5 == 0) { // reduce noise
                        airbrake.notify(msg)
                      }
                    }
                  }
                } else if (runMillis > LONG_RUNNING_THRESHOLD) {
                  log.warn(s"[terminator] potential long ($runMillis ms) running task: $sc; stackTrace=${sc.threadRef.get.getStackTrace.mkString("\n")}")
                } else {
                  log.info(s"[terminator] $sc has been running for $runMillis ms")
                }
              }
            } orElse {
              removeRef(iter) // Some(s"[terminator] remove collected entry $ref from queue")
              None
            }
          }

          if (config.pull) { // todo: move this out
            log.info(s"[puller] look for things to do ... q.size=${submittedQ.size()}")
            if (submittedQ.isEmpty) {
              serviceDiscovery.thisInstance map { inst =>
                if (inst.isHealthy) {
                  asyncHelper.assignTasks(inst.id.id, Runtime.getRuntime.availableProcessors()) onComplete { trRequests =>
                    trRequests match {
                      case Failure(t) =>
                        log.error(s"[puller] Caught exception ${t} while pulling for tasks",t) // move along
                      case Success(requests) =>
                        log.info(s"[puller(${inst.id.id}})] assigned (${requests.length}) scraping tasks: ${requests.map(r => s"[uriId=${r.uri.id},infoId=${r.info.id},url=${r.uri.url}]").mkString(",")} ")
                        for (sr <- requests) {
                          asyncScrape(sr.uri, sr.info, sr.proxyOpt)
                        }
                    }
                  }
                }
              }
            }
          }
        }
      } catch {
        case t:Throwable => logErr(t, "terminator", submittedQ.toString, true)
      }
    }
  }

  val scheduler = Executors.newSingleThreadScheduledExecutor
  scheduler.scheduleWithFixedDelay(terminator, config.scrapePendingFrequency, config.scrapePendingFrequency * 2, TimeUnit.SECONDS)

  private def worker = new SyncScraper(airbrake, config, httpFetcher, extractorFactory, articleStore, s3ScreenshotStore, helper)
  def asyncScrape(nuri: NormalizedURI, scrapeInfo: ScrapeInfo, proxy: Option[HttpProxy]): Unit = {
    log.info(s"[QScraper.asyncScrape($fjPool)] uri=$nuri info=$scrapeInfo proxy=$proxy")
    try {
      val callable = new ScrapeCallable(System.currentTimeMillis, nuri, scrapeInfo, proxy) {
        def doWork: (NormalizedURI, Option[Article]) = worker.safeProcessURI(uri, info, proxyOpt)
      }
/*
      val callable = new ScrapeCallable(System.currentTimeMillis, new AtomicLong, nuri, scrapeInfo, proxy) {
        def call(): (NormalizedURI, Option[Article]) = {
          val name = Thread.currentThread.getName
          callTS.set(System.currentTimeMillis)
          Thread.currentThread().setName(s"$name##${toString}")
          threadRef.set(Thread.currentThread())
          try {
            val res = timing(s"QScraper.safeProcessURI(${uri.id}),${uri.url})") {
              worker.safeProcessURI(uri, info, proxyOpt)
            }
            val ts = System.currentTimeMillis
            if (runMillis(ts) > LONG_RUNNING_THRESHOLD)
              log.warn(s"[QScraper] LONG (${runDuration(ts)}) running scraping task:${toString}; ${fjPool}")
            res
          } catch {
            case t:Throwable =>
              exRef.set(t)
              logErr(t, "QScraper", uri.toString, true)
              (nuri, None)
          } finally {
            threadRef.set(null)
            Thread.currentThread().setName(name)
          }
        }
      }
*/
      val fjTask = fjPool.submit(callable)
      if (!fjTask.isDone) {
        submittedQ.offer(WeakReference(callable, fjTask)) // should never return false
      }
    } catch {
      case t:Throwable => logErr(t, "QScraper", s"uri=$nuri, info=$scrapeInfo", true)
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