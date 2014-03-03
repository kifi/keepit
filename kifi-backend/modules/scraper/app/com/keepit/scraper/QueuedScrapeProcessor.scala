package com.keepit.scraper

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference, AtomicLong}
import com.keepit.model._
import com.keepit.search.{ArticleStore, Article}
import org.joda.time.DateTime
import scala.concurrent.duration.Duration
import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.extractor._
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.logging.Logging
import play.api.Play
import scala.ref.WeakReference
import com.keepit.common.performance.timing
import org.apache.http.HttpStatus
import play.api.Play.current
import play.api.libs.json.Json
import com.keepit.common.zookeeper.ServiceDiscovery
import scala.util.{Try, Success, Failure}
import scala.concurrent.Future
import com.keepit.common.net.HttpClient
import com.keepit.common.plugin.SchedulingProperties
import java.util.concurrent.{Callable, TimeUnit, Executors, ConcurrentLinkedQueue}
import scala.concurrent.forkjoin.{ForkJoinTask, ForkJoinPool}
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.akka.SafeFuture

abstract class TracedCallable[T](val submitTS:Long = System.currentTimeMillis) extends Callable[Try[T]] {

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

  def getTaskDetails(name:String): ScraperTaskDetails

  def call(): Try[T] = {
    threadRef.set(Thread.currentThread())
    callTS.set(System.currentTimeMillis)
    val name = Thread.currentThread.getName
    Thread.currentThread().setName(Json.stringify(Json.toJson(getTaskDetails(name))))
    try {
      val r = Try(doWork)
      trRef.set(r)
      r
    } finally {
      threadRef.set(null)
      Thread.currentThread().setName(name)
    }
  }

  override def toString = Thread.currentThread.getName
}

object TracedCallable {
  implicit class AtomicRef[T](val underlying:AtomicReference[T]) extends AnyVal {
    def asOpt[T] = {
      val r = underlying.get
      if (r == null) None else Some(r)
    }
  }
}

abstract class ScrapeCallable(val uri:NormalizedURI, val info:ScrapeInfo, val proxyOpt:Option[HttpProxy])
  extends TracedCallable[(NormalizedURI, Option[Article])] {
  override def getTaskDetails(name:String) = ScraperTaskDetails(name, uri.url, submitDateTime, callDateTime, ScraperTaskType.SCRAPE, uri.id, info.id, Some(killCount.get), None)
}

@Singleton
class QueuedScrapeProcessor @Inject() (
  val airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  httpClient: HttpClient,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  serviceDiscovery: ServiceDiscovery,
  asyncHelper: ShoeboxDbCallbacks,
  schedulingProperties: SchedulingProperties,
  helper: SyncShoeboxDbCallbacks) extends ScrapeProcessor with Logging with ScraperUtils {

  val LONG_RUNNING_THRESHOLD = if (Play.isDev) 200 else config.queueConfig.terminateThreshold
  val Q_SIZE_THRESHOLD = config.queueConfig.queueSizeThreshold
  val pSize = Runtime.getRuntime.availableProcessors * 128
  val fjPool = new ForkJoinPool(pSize) // consider merging with ExecutionContext.fj (# of threads too low -- make configurable)
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

  val NUM_CORES = Runtime.getRuntime.availableProcessors
  val PULL_MAX = NUM_CORES * config.pullMultiplier
  val PULL_THRESHOLD = config.queueConfig.pullThreshold.getOrElse(NUM_CORES / 2)

  override def pull():Unit = {
    log.info(s"[QScraper.puller] look for things to do ... q.size=${submittedQ.size} threshold=${PULL_THRESHOLD}")
    if (submittedQ.isEmpty || submittedQ.size <= PULL_THRESHOLD) {
      serviceDiscovery.thisInstance.map{ inst =>
        if (inst.isHealthy) {
          asyncHelper.assignTasks(inst.id.id, PULL_MAX).onComplete{ trRequests =>
            trRequests match {
              case Failure(t) =>
                log.error(s"[puller(${inst.id.id})] Caught exception ${t} while pulling for tasks", t) // move along
              case Success(requests) =>
                log.info(s"[puller(${inst.id.id})] assigned (${requests.length}) scraping tasks: ${requests.map(r => s"[uriId=${r.uri.id},infoId=${r.info.id},url=${r.uri.url}]").mkString(",")} ")
                for (sr <- requests) {
                  asyncScrape(sr.uri, sr.info, sr.proxyOpt)
                }
            }
          }(ExecutionContext.fj)
        }
      }
    }
  }

  import TracedCallable._
  val terminator = new Runnable {
    def run(): Unit = {
      try {
        log.info(s"[terminator] checking for long-running tasks to kill q.size=${submittedQ.size} fj(#submit)=${fjPool.getQueuedSubmissionCount} fj(#task)=${fjPool.getQueuedTaskCount} ...")
        if (!submittedQ.isEmpty) { // some low threshold
          val iter = submittedQ.iterator
          while (iter.hasNext) {
            val curr = System.currentTimeMillis
            val ref = iter.next
            ref.get map { case (sc, fjTask) =>
              if (sc.callTS.get == 0) log.info(s"[terminator] $sc has not yet started")
              else if (fjTask.isDone) removeRef(iter)
              else if (sc.trRef.asOpt.isDefined && sc.trRef.get.isFailure) removeRef(iter, Some(s"[terminator] ${sc} isFailure=true; ${sc.trRef.get}; remove from q"))
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
                      val msg = s"[terminator] (${killCount}) attempts failed to terminate LONG ($runMillis ms) running task: $sc; $fjTask; stackTrace=${sc.threadRef.get.getStackTrace.mkString("|")}"
                      log.error(msg)
                      if (killCount % 5 == 0) { // reduce noise
                        airbrake.notify(msg)
                      }
                    }
                  }
                } else if (runMillis > LONG_RUNNING_THRESHOLD) {
                  log.warn(s"[terminator] potential long ($runMillis ms) running task: $sc; stackTrace=${sc.threadRef.get.getStackTrace.mkString("|")}")
                } else {
                  log.info(s"[terminator] $sc has been running for $runMillis ms")
                }
              }
            } orElse {
              removeRef(iter) // collected
              None
            }
          }
        }
      } catch {
        case t:Throwable => logErr(t, "terminator", submittedQ.toString, true)
      }
    }
  }

  val scheduler = Executors.newSingleThreadScheduledExecutor
  val TERMINATOR_FREQ: Int = config.queueConfig.terminatorFreq
  if (schedulingProperties.enabled){
    scheduler.scheduleWithFixedDelay(terminator, TERMINATOR_FREQ, TERMINATOR_FREQ, TimeUnit.SECONDS)
  }

  private def worker = new SyncScraper(airbrake, config, httpFetcher, httpClient, extractorFactory, articleStore, s3ScreenshotStore, helper)
  def asyncScrape(nuri: NormalizedURI, scrapeInfo: ScrapeInfo, proxy: Option[HttpProxy]): Unit = {
    log.info(s"[QScraper.asyncScrape($fjPool)] uri=$nuri info=$scrapeInfo proxy=$proxy")
    try {
      val callable = new ScrapeCallable(nuri, scrapeInfo, proxy) {
        def doWork: (NormalizedURI, Option[Article]) = worker.safeProcessURI(uri, info, proxyOpt)
      }
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
    val extractor = extractorProviderTypeOpt match {
      case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
      case _ => extractorFactory(url)
    }

    val callable = new TracedCallable[Option[BasicArticle]] {
      def doWork = {
        val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
        val res = fetchStatus.statusCode match {
          case HttpStatus.SC_OK if !(helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) => Some(worker.basicArticle(url, extractor))
          case _ => None
        }
        res
      }
      override def getTaskDetails(name:String) = ScraperTaskDetails(name, url, submitDateTime, callDateTime, ScraperTaskType.FETCH_BASIC, None, None, None, extractorProviderTypeOpt map {_.name})
    }
    val jf = fjPool.submit(callable)
    SafeFuture{
     jf.get(LONG_RUNNING_THRESHOLD, TimeUnit.MILLISECONDS) match {
       case Success(articleOpt) => articleOpt
       case Failure(t) => None
     }
    }(ExecutionContext.fj)
  }
}