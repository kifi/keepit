package com.keepit.search.engine

import com.keepit.common.logging.Logging
import scala.math.max

class SearchTimeLogs(startTime: Long = System.currentTimeMillis()) extends Logging {

  private[this] var _socialGraphInfo: Long = 0L
  private[this] var _clickBoost: Long = 0L
  private[this] var _queryParsing: Long = 0L
  private[this] var _personalizedSearcher: Long = 0L
  private[this] var _search: Long = 0L
  private[this] var _processHits: Long = 0L
  private[this] var _endTime: Long = 0L

  def socialGraphInfo(now: Long = System.currentTimeMillis()): Unit = { _socialGraphInfo = now }
  def clickBoost(now: Long = System.currentTimeMillis()): Unit = { _clickBoost = now }
  def queryParsing(now: Long = System.currentTimeMillis()): Unit = { _queryParsing = now }
  def personalizedSearcher(now: Long = System.currentTimeMillis()): Unit = { _personalizedSearcher = now }
  def search(now: Long = System.currentTimeMillis()): Unit = { _search = now }
  def processHits(now: Long = System.currentTimeMillis()): Unit = { _processHits = now }
  def done(now: Long = System.currentTimeMillis()): Unit = { _endTime = now }

  def elapsed(time: Long = System.currentTimeMillis()): Long = (time - startTime)

  def send(): Unit = {
    send("mainSearch.socialGraphInfo", _socialGraphInfo, ALWAYS)
    send("mainSearch.queryParsing", _queryParsing, ALWAYS)
    send("mainSearch.getClickboost", _clickBoost, ALWAYS)
    send("mainSearch.personalizedSearcher", _personalizedSearcher, ALWAYS)
    send("mainSearch.LuceneSearch", _search, ALWAYS)
    send("mainSearch.processHits", _processHits, ALWAYS)
    send("mainSearch.total", _endTime, ALWAYS)
  }

  @inline
  private def send(name: String, time: Long, frequency: Double) = {
    if (time > 0L) statsd.timing(name, elapsed(time), frequency)
  }

  private def timeLine: List[(String, Long)] = {
    List(
      ("socialGraphInfo", _socialGraphInfo),
      ("queryParsing", _queryParsing),
      ("clickBoost", _clickBoost),
      ("personalizedSearcher", _personalizedSearcher),
      ("luceneSearch", _search),
      ("processHits", _processHits)
    ).filter(_._2 > 0L).sortBy(_._2)
  }

  override def toString() = {
    val total = elapsed(_endTime)
    val detail = timeLine.map(event => s"${event._1}=${elapsed(event._2)}").mkString("[", ", ", "]")

    s"search time summary: total=$total, detail=$detail"
  }
}
