package com.keepit.search

import java.io.InputStreamReader
import java.io.Reader
import java.lang.{ Double => JDouble }
import java.util.jar.JarFile
import java.util.{ HashMap => JHashMap }

import scala.collection.JavaConversions._
import scala.math.min

import com.keepit.common.logging.Logging
import com.keepit.search.langdetector.DetectorFactory
import com.keepit.search.langdetector.LangDetectException
import com.keepit.common.strings._

object LangDetector extends Logging {
  initialize

  val en = Lang("en")

  private def initialize {
    // find profile resources in Cybozu langdetect jar file. This is not a standard way to load profiles.
    var path = classOf[com.cybozu.labs.langdetect.DetectorFactory].getResource("DetectorFactory.class").toString
    if (path.startsWith("jar:file:")) path = path.substring(9)
    path = path.substring(0, path.indexOf("!"))
    log.debug("loading language detection profiles from %s".format(path))
    val jarFile = new JarFile(path)
    val profiles = jarFile.entries.filter { _.getName.startsWith("profiles/") }.map { entry =>
      val reader = new InputStreamReader(jarFile.getInputStream(entry), UTF8)
      readerToString(reader) // this should never fail. if fails, lang-detect won't work correctly. let it throw exception.
    }.toList
    DetectorFactory.loadProfile(profiles)
    log.debug("completed profile loading")
  }

  val languages: Set[Lang] = DetectorFactory.getLangList().filter(_ != null).map(Lang(_)).toSet

  val uniformPriorMap = {
    val langList = DetectorFactory.getLangList
    val prob = 1.0f / langList.size()
    langList.foldLeft(Map.empty[String, Double]) { (m, lang) => m + (lang -> prob) }
  }

  val biasedProbability = 0.5d
  val priorMapForBiasedDetection = {
    val langList = DetectorFactory.getLangList
    val langListSize = langList.size
    val prob = (1.0d - biasedProbability) / (langListSize - 1)
    langList.foldLeft(Map.empty[String, Double]) { (m, lang) => m + (lang -> prob) }
  }

  private def makePriorMap(given: Map[Lang, Double]): JHashMap[String, JDouble] = {
    val givenMap = given.map { case (k, v) => k.lang -> v }
    val siz = given.size
    val sum = min(given.values.sum, 1.0)
    val langList = DetectorFactory.getLangList
    val langListSize = langList.size
    val prob = (1.0d - sum) / (langListSize - siz)

    new JHashMap(
      langList.foldLeft(Map.empty[String, JDouble]) { (m, lang) =>
        m + (lang -> new JDouble(givenMap.getOrElse(lang, prob)))
      }
    )
  }

  private def readerToString(reader: Reader) = {
    val sb = new StringBuilder
    var c = reader.read()
    while (c >= 0) {
      sb.append(c.asInstanceOf[Char])
      c = reader.read()
    }
    sb.toString
  }

  private def makeDetector(text: String, priorMap: Map[Lang, Double] = Map()) = {
    val detector = DetectorFactory.create()
    // limit the iteration in the detection process when the text is short
    val iterationLimit = (detector.getIterationLimit.toDouble * (1.0d - (1.0d / (1.0d - ((text.length + 1).toDouble / 30.0d))))).toInt //TODO: this hyperbolic function has problems. need to modify this.
    detector.setIterationLimit(iterationLimit)
    detector.setProbabilityThreshold(0.0d)
    detector.setPriorMap(makePriorMap(priorMap))
    detector.append(text)
    detector
  }

  def detect(text: String, lang: Lang): Lang = {
    detect(text, Map(lang -> biasedProbability), lang)
  }

  def detect(text: String, priorMap: Map[Lang, Double] = Map(), default: Lang = Lang("en")): Lang = {
    try {
      makeDetector(text, priorMap).detect() match {
        case "unknown" => default
        case lang => Lang(lang)
      }
    } catch {
      case e: LangDetectException => default // no text? defaulting to English
    }
  }

  def detectShortText(text: String, lang: Lang): Lang = {
    detectShortText(text, Map(lang -> biasedProbability), lang)
  }

  def detectShortText(text: String, priorMap: Map[Lang, Double] = Map(), default: Lang = Lang("en")): Lang = {
    val detector = DetectorFactory.create()
    detector.append(text)
    detector.setPriorMap(makePriorMap(priorMap))
    try {
      detector.detectForShort() match {
        case "unknown" => default
        case lang => Lang(lang)
      }
    } catch {
      case e: LangDetectException => default
    }
  }

  def probabilities(text: String, lang: Lang): Seq[(Lang, Double)] = {
    probabilities(text, Map(lang -> biasedProbability))
  }

  def probabilities(text: String, priorMap: Map[Lang, Double] = Map()): Seq[(Lang, Double)] = {
    try {
      makeDetector(text, priorMap).getProbabilities().map { language => (Lang(language.lang), language.prob) }.toSeq
    } catch {
      case e: LangDetectException => Seq.empty[(Lang, Double)] // no text?
    }
  }
}
