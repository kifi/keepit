package com.keepit.search

import java.io.InputStreamReader
import java.io.Reader
import java.lang.{Double => JDouble}
import java.util.jar.JarFile
import java.util.{HashMap => JHashMap}
import scala.collection.JavaConversions._
import com.keepit.common.logging.Logging
import com.keepit.search.langdetector.DetectorFactory
import com.keepit.search.langdetector.LangDetectException


object LangDetector extends Logging {
  initialize

  private def initialize {
    // find profile resources in Cybozu langdetect jar file. This is not a standard way to load profiles.
    var path = classOf[com.cybozu.labs.langdetect.DetectorFactory].getResource("DetectorFactory.class").toString
    if (path.startsWith("jar:file:")) path = path.substring(9)
    path = path.substring(0, path.indexOf("!"))
    log.debug("loading language detection profiles from %s".format(path))
    val jarFile = new JarFile(path)
    val profiles = jarFile.entries.filter{ _.getName.startsWith("profiles/") }.map{ entry =>
      val reader = new InputStreamReader(jarFile.getInputStream(entry), "UTF-8")
      readerToString(reader) // this should never fail. if fails, lang-detect won't work correctly. let it throw exception.
    }.toList
    DetectorFactory.loadProfile(profiles)
    log.debug("completed profile loading")
  }

  val languages: Seq[Lang] = DetectorFactory.getLangList().map(Lang(_)).toSeq

  val biasedProbability = 0.5d
  val priorMapForBiasedDetection = {
    val langList = DetectorFactory.getLangList
    val langListSize = langList.size
    val prob = (1.0d - biasedProbability) / (langListSize - 1)
    langList.foldLeft(Map.empty[String, Double]){ (m, lang) => m + (lang -> prob) }
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

  private def makeDetector(text: String, priorMap: Option[Map[String, Double]]) = {
    val detector = DetectorFactory.create()
    // limit the iteration in the detection process when the text is short
    val iterationLimit = (detector.getIterationLimit.toDouble * (1.0d - (1.0d / (1.0d - ((text.length + 1).toDouble / 30.0d))))).toInt			//TODO: this hyperbolic function has problems. need to modify this.
    detector.setIterationLimit(iterationLimit)
    detector.setProbabilityThreshold(0.0d)
    priorMap match {
      case Some(priorMap) => detector.setPriorMap(new JHashMap(priorMap.mapValues{ v => new JDouble(v) }))
      case None =>
    }
    detector.append(text)
    detector
  }

  def detect(text: String, lang: Lang): Lang = {
    detect(text, Some(priorMapForBiasedDetection + (lang.lang -> biasedProbability)))
  }

  def detect(text: String, priorMap: Option[Map[String, Double]] = None): Lang = {
    try {
      makeDetector(text, priorMap).detect() match {
        case "unknown" => Lang("en")
        case lang => Lang(lang)
      }
    } catch {
      case e: LangDetectException => Lang("en") // no text? defaulting to English
    }
  }

  def detectShortText(text:String, lang: Lang): Lang = {
    detectShortText(text, generateBiasedPriorMap(lang))
  }

  def detectShortText(text:String): Lang = {
    detectShortText(text, generateUniformPriorMap)
  }

  private def generateBiasedPriorMap(biasedLang:Lang) = {
    val biasedProb = 0.5d
    val langList = DetectorFactory.getLangList
    val prob = (1.0d - biasedProb)/ (langList.size()-1)				// assume size > 1
    var prior = Map.empty[String, Double]
    for(lang<-langList){
      prior += (lang -> prob)
    }
    prior += (biasedLang.lang-> biasedProb)
    prior
  }

  private def generateUniformPriorMap() = {
    val langList = DetectorFactory.getLangList
    val prob = 1.0f/langList.size()				// assume size > 0
    var prior = Map.empty[String, Double]
    for(lang<-langList){
      prior += (lang -> prob)
    }
    prior
  }

  private def detectShortText(text:String,  prior: Map[String, Double]): Lang = {
    val detector = DetectorFactory.create()
    detector.append(text)
    detector.setPriorMap(new JHashMap(prior.mapValues{ v => new JDouble(v) }))
    try{
      detector.detectForShort() match{
        case "unknown" => Lang("en")
        case lang => Lang(lang)
      }
    } catch{
      case e: LangDetectException => Lang("en")
    }
  }

  def probabilities(text: String, lang: Lang): Seq[(Lang, Double)] = {
    probabilities(text, Some(priorMapForBiasedDetection + (lang.lang -> 0.6d)))
  }

  def probabilities(text: String, priorMap: Option[Map[String, Double]] = None): Seq[(Lang, Double)] = {
    try {
      makeDetector(text, priorMap).getProbabilities().map{ language => (Lang(language.lang), language.prob) }.toSeq
    } catch {
      case e: LangDetectException => Seq.empty[(Lang, Double)] // no text?
    }
  }
}