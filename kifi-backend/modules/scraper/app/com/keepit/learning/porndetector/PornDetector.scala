package com.keepit.learning.porndetector

import com.keepit.common.logging.Logging

import scala.math.{ log, exp }
import scala.util.matching.Regex

trait PornDetector {
  def posterior(text: String): Float // probability being porn
  def isPorn(text: String): Boolean
}

/**
 * likelihoodRatio: prob(word | porn) / prob(word | non-porn)
 * priorOfPorn: prior probability of porn
 * oovRatio: likelihoodRatio for out-of-vocabulary token
 * This is meant to be a detector for short text (e.g  less than 10 tokens). For bigger text, use sliding window to detect "porn part"
 */

class NaiveBayesPornDetector(
    likelihoodRatio: Map[String, Float],
    priorOfPorn: Float = 0.5f,
    oovRatio: Float = 0.001f) extends PornDetector {

  private def logPosteriorRatio(text: String): Double = {
    PornDetectorUtil.tokenize(text).foldLeft(0.0) {
      case (score, token) =>
        score + log(likelihoodRatio.getOrElse(token, oovRatio).toDouble)
    } + log(priorOfPorn.toDouble / (1 - priorOfPorn).toDouble)
  }

  override def posterior(text: String): Float = {
    val logProb = logPosteriorRatio(text)
    if (logProb > 10) 1f
    else if (logProb < -10) 0f
    else { val ratio = exp(logProb); (ratio / (ratio + 1)).toFloat }
  }

  override def isPorn(text: String): Boolean = posterior(text) >= 0.75f // shifted threshold
}

class SlidingWindowPornDetector(detector: PornDetector, windowSize: Int = 10) extends PornDetector with Logging {
  if (windowSize <= 4) throw new IllegalArgumentException(s"window size for SlidingWindowPornDetector too small: get ${windowSize}, need at least 4")
  def detectBlocks(text: String): (Int, Int) = {
    val blocks = PornDetectorUtil.tokenize(text).sliding(windowSize, windowSize).toArray
    val bad = blocks.filter { b => detector.isPorn(b.mkString(" ")) }
    (blocks.size, bad.size)
  }

  override def isPorn(text: String): Boolean = posterior(text) > 0.5f

  override def posterior(text: String): Float = {
    log.info(s"[SlidingWindowPornDetector]: detecting for: ${text.take(100)}")
    val (blocks, badBlocks) = detectBlocks(text)
    if (blocks == 0) return 0f
    val r = badBlocks / blocks.toFloat
    log.info(s"[SlidingWindowPornDetector]: ratio = ${r}, num of bad blocks: ${badBlocks}")
    if (r > 0.05 || badBlocks > 10) return 1f else 0f // not smooth (for performance reason). could use more Bayesian style
  }
}

object PornDetectorUtil {
  def tokenize(text: String): Array[String] = {
    text.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map { _.toLowerCase }
  }
}

object PornDomains {
  private def toRegex(domain: String): Regex = {
    val domainRegex = domain.toLowerCase.replaceAllLiterally(".", "\\.")
    new Regex("""^https?:\/\/.*""" + domainRegex + "(/.*)?")
  }

  private val domains: Seq[String] = Seq("2porno.tv", "3xhen.com", "4tube.com", "4sex4.com", "alotporn.com", "alphaporno.com", "anyporn.com", "anysex.com", "backpage.com", "beeg.com", "bravotube.net", "cityvibe.com", "cum.com", "drtuber.com", "empflix.com", "eskimotube.com", "extremetube.com", "fapdu.com", "fc2.com", "h2porn.com", "hardsextube.com", "hellporno.com", "highfashion4less.com", "hotmovies.com", "hotshame.com", "imagefap.com", "javfree.me", "jizzhut.com", "keezmovies.com", "madthumbs.com", "motherless.com", "nuvid.com", "porn.com", "pornerbros.com", "pornhub.com", "pornoid.com", "pornoxo.com", "pornsharing.com", "porntube.com", "redtube.com", "s3exy.com", "slutload.com", "spankwire.com", "surfgayvideo.com", "sweetkiss.me", "tnaflix.com", "touchbyvenus.com", "tube8.com", "tubepornstars.com", "tubewolf.com", "vid2c.com", "vintagetubesex.com", "vporn.com", "wanknews.com", "warnet.ws", "woodmancastingx.com", "xblboys.com", "xhamster.com", "xnxx.com", "xtube.com", "xvideos.com", "xxxbunker.com", "xxxkinky.com", "yobt.com", "youjizz.com", "youngpornvideos.com", "yourlust.com", "youporn.com")
  private val domainRegexes = domains.map { toRegex(_) }
  def isPornDomain(url: String): Boolean = {
    val urlLower = url.toLowerCase
    domainRegexes.exists(reg => reg.findFirstMatchIn(urlLower).isDefined)
  }
}
