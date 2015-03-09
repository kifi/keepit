package com.keepit.rover.fetcher.apache

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import org.apache.http.StatusLine
import org.apache.http.client.methods.HttpGet
import play.api.Play
import play.api.Play.current
import org.apache.http.HttpStatus._

import scala.ref.WeakReference
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import java.util.concurrent.ConcurrentLinkedQueue

case class FetchInfo(url: String, ts: Long, htpGet: HttpGet, thread: Thread) {
  val killCount = new AtomicInteger()
  val respStatusRef = new AtomicReference[StatusLine]()
  val exRef = new AtomicReference[Throwable]()
  override def toString = s"[Fetch($url,${ts},${thread.getName})] isAborted=${htpGet.isAborted} killCount=${killCount} respRef=${respStatusRef} exRef=${exRef}"
}

class HttpFetchEnforcer(q: ConcurrentLinkedQueue[WeakReference[FetchInfo]], Q_SIZE_THRESHOLD: Int, airbrake: AirbrakeNotifier) extends Runnable with Logging {

  val LONG_RUNNING_THRESHOLD = if (Play.maybeApplication.isDefined && Play.isDev) 1000 else sys.props.get("fetcher.abort.threshold") map (_.toInt) getOrElse (2 * 1000 * 60) // Play reference can be removed

  def run(): Unit = {
    try {
      log.debug(s"[enforcer] checking for long running fetch requests ... q.size=${q.size}")
      if (!q.isEmpty) {
        val iter = q.iterator
        while (iter.hasNext) {
          val curr = System.currentTimeMillis
          val ref = iter.next
          ref.get map {
            case ft: FetchInfo =>
              if (ft.respStatusRef.get != null) {
                val sc = ft.respStatusRef.get.getStatusCode
                removeRef(iter, if (sc != SC_OK && sc != SC_NOT_MODIFIED) Some(s"[enforcer] ${ft.url} finished with abnormal status:${ft.respStatusRef.get}") else None)
              } else if (ft.exRef.get != null) removeRef(iter, Some(s"[enforcer] ${ft.url} caught error ${ft.exRef.get}; remove from q"))
              else if (ft.htpGet.isAborted) removeRef(iter, Some(s"[enforcer] ${ft.url} is aborted; remove from q"))
              else {
                val runMillis = curr - ft.ts
                if (runMillis > LONG_RUNNING_THRESHOLD * 2) {
                  val msg = s"[enforcer] attempt# ${ft.killCount.get} to abort long ($runMillis ms) fetch task: ${ft.htpGet.getURI}"
                  log.warn(msg)
                  ft.htpGet.abort() // inform scraper
                  ft.killCount.incrementAndGet()
                  log.debug(s"[enforcer] ${ft.htpGet.getURI} isAborted=${ft.htpGet.isAborted}")
                  if (!ft.htpGet.isAborted) {
                    log.warn(s"[enforcer] failed to abort long ($runMillis ms) fetch task $ft; calling interrupt ...")
                    ft.thread.interrupt
                    if (ft.thread.isInterrupted) {
                      log.warn(s"[enforcer] thread ${ft.thread} has been interrupted for fetch task $ft")
                      // removeRef -- maybe later
                    } else {
                      val msg = s"[enforcer] attempt# ${ft.killCount.get} failed to interrupt ${ft.thread} for fetch task $ft"
                      log.error(msg)
                      if (ft.killCount.get % 5 == 0)
                        airbrake.notify(msg)
                    }
                  }
                } else if (runMillis > LONG_RUNNING_THRESHOLD) {
                  log.warn(s"[enforcer] potential long ($runMillis ms) running task: $ft; stackTrace=${ft.thread.getStackTrace.mkString("|")}")
                } else {
                  log.debug(s"[enforcer] $ft has been running for $runMillis ms")
                }
              }
          } orElse {
            removeRef(iter)
            None
          }
        }
      }
      if (q.size > Q_SIZE_THRESHOLD) {
        airbrake.notify(s"[enforcer] q.size (${q.size}) crossed threshold ($Q_SIZE_THRESHOLD)")
      } else if (q.size > Q_SIZE_THRESHOLD / 2) {
        log.warn(s"[enforcer] q.size (${q.size}) crossed threshold/2 ($Q_SIZE_THRESHOLD)")
      }
    } catch {
      case t: Throwable =>
        airbrake.notify(s"[enforcer] Caught exception $t; queue=$q; cause=${t.getCause}; stack=${t.getStackTraceString}")
    }
  }

  private def removeRef(iter: java.util.Iterator[_], msgOpt: Option[String] = None) {
    try {
      for (msg <- msgOpt)
        log.info(msg)
      iter.remove()
    } catch {
      case t: Throwable =>
        log.error(s"[terminator] Caught exception $t; (cause=${t.getCause}) while attempting to remove entry from queue")
    }
  }
}
