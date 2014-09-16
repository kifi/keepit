package com.keepit.search.engine

import com.keepit.common.logging.Logging

class SearchTimeLogs(startTime: Long = System.currentTimeMillis()) extends Logging {

  private[this] var _socialGraphInfo: Long = 0
  private[this] var _clickBoost: Long = 0
  private[this] var _queryParsing: Long = 0
  private[this] var _personalizedSearcher: Long = 0
  private[this] var _search: Long = 0
  private[this] var _processHits: Long = 0
  private[this] var _endTime: Long = 0

  def socialGraphInfo(now: Long = System.currentTimeMillis()): Unit = { _socialGraphInfo = now }
  def clickBoost(now: Long = System.currentTimeMillis()): Unit = { _clickBoost = now }
  def queryParsing(now: Long = System.currentTimeMillis()): Unit = { _queryParsing = now }
  def personalizedSearcher(now: Long = System.currentTimeMillis()): Unit = { _personalizedSearcher = now }
  def search(now: Long = System.currentTimeMillis()): Unit = { _search = now }
  def processHits(now: Long = System.currentTimeMillis()): Unit = { _processHits = now }
  def done(now: Long = System.currentTimeMillis()): Unit = { _endTime = now }

  def elapsed(time: Long = System.currentTimeMillis()): Long = (time - startTime)

  def send(): Unit = {
    statsd.timing("mainSearch.socialGraphInfo", elapsed(_socialGraphInfo), ALWAYS)
    statsd.timing("mainSearch.queryParsing", elapsed(_queryParsing), ALWAYS)
    statsd.timing("mainSearch.getClickboost", elapsed(_clickBoost), ALWAYS)
    statsd.timing("mainSearch.personalizedSearcher", elapsed(_personalizedSearcher), ALWAYS)
    statsd.timing("mainSearch.LuceneSearch", elapsed(_search), ALWAYS)
    statsd.timing("mainSearch.processHits", elapsed(_processHits), ALWAYS)
    statsd.timing("mainSearch.total", elapsed(_endTime), ALWAYS)
  }

  private def timeLine: List[(String, Long)] = {
    List(
      ("socialGraphInfo", _socialGraphInfo),
      ("queryParsing", _queryParsing),
      ("clickBoost", _clickBoost),
      ("personalizedSearcher", _personalizedSearcher),
      ("luceneSearch", _search),
      ("processHits", _processHits)
    ).sortBy(_._2)
  }

  override def toString() = {
    val total = elapsed(_endTime)
    val detail = timeLine.map(event => s"${event._1}=${event._2}").mkString("[", ", ", "]")

    s"search time summary: total = $total, detail=$detail"
  }
}
