package com.keepit.scraper.extractor

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ Host, URI }
import com.keepit.scraper.ScraperConfig
import org.jsoup.nodes.Document
import com.google.inject.{ Inject, Singleton }
import scala.util.matching.Regex
import java.util.concurrent.atomic.AtomicLong

@Singleton
class SimpleJsoupBasedExtractorProvider @Inject() (airbrake: AirbrakeNotifier) extends ExtractorProvider with Logging {

  private[this] val lastWarned = new AtomicLong(0L)

  def warn(msg: String): Unit = {
    log.warn(msg)
    val last = lastWarned.get
    if (last + 600000 < System.currentTimeMillis) {
      if (lastWarned.compareAndSet(last, System.currentTimeMillis)) airbrake.notify(s"The page format may have changed. Check the log for warning messages.")
    }
  }

  private case class Rule(hostPattern: Option[Regex], pathPattern: Option[Regex], selectors: Seq[(String, Boolean)])

  private val rules: List[Rule] = List(
    // Cookpad
    Rule(Some("""(^|\.)cookpad\.com$""".r), Some("""^/recipe/list/""".r), Seq((".main-cont", true))),
    Rule(Some("""(^|\.)cookpad\.com$""".r), Some("""^/recipe/[^/]+/tsukurepos""".r), Seq(("#main", true))),
    Rule(Some("""(^|\.)cookpad\.com$""".r), Some("""^/recipe/[^/]+($|/$)""".r), Seq(("#recipe", true)))
  )

  private[this] def findRule(uri: URI): Option[Rule] = {
    uri match {
      case URI(_, _, hostOpt, _, pathOpt, _, _) =>
        rules.find { rule =>
          rule.hostPattern.forall { p => hostOpt.exists { host => p.findFirstIn(host.toString).isDefined } } &&
            rule.pathPattern.forall { p => pathOpt.exists { path => p.findFirstIn(path).isDefined } }
        }
      case _ => None
    }
  }

  def isDefinedAt(uri: URI) = findRule(uri).isDefined

  def apply(uri: URI) = {
    findRule(uri) match {
      case Some(rule) => new SimpleJsoupBasedExtractor(uri.toString(), ScraperConfig.maxContentChars, rule.selectors, this)
      case None => throw new IllegalArgumentException(s"no matching uri=${uri.toString}")
    }
  }
}

class SimpleJsoupBasedExtractor(url: String, maxContentChars: Int, selectors: Seq[(String, Boolean)], provider: SimpleJsoupBasedExtractorProvider) extends JsoupBasedExtractor(url, maxContentChars) with Logging {
  def parse(doc: Document): String = {
    val content = selectors.map {
      case (selector, required) =>
        val elements = doc.select(selector)
        if (required && elements.isEmpty) provider.warn(s"A required element ($selector) is missing. The page format may have changed. : $url")
        elements.text
    }.mkString("\n")

    if (content.length == 0) provider.warn(s"No content extracted. The page format may have changed. : $url")

    content
  }
}

