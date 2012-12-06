package com.keepit.search

import java.io.InputStreamReader
import java.io.Reader
import java.lang.{Double => JDouble}
import java.util.jar.JarFile
import java.util.{HashMap => JHashMap}
import scala.Option.option2Iterable
import scala.collection.JavaConversions._
import com.cybozu.labs.langdetect.DetectorFactory
import com.cybozu.labs.langdetect.LangDetectException
import com.keepit.common.logging.Logging

object LangDetector extends Logging {
  initialize

  private def initialize {
    var path = classOf[DetectorFactory].getResource("DetectorFactory.class").toString
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

  private def readerToString(reader: Reader) = {
    val sb = new StringBuilder
    var c = reader.read()
    while (c >= 0) {
      sb.append(c.asInstanceOf[Char])
      c = reader.read()
    }
    sb.toString
  }

  def detect(text: String, priorMap: Option[Map[String, Double]] = None) = {
    val detector = DetectorFactory.create()
    priorMap match {
      case Some(priorMap) => detector.setPriorMap(new JHashMap(priorMap.mapValues{ v => new JDouble(v) }))
      case None =>
    }
    detector.append(text)
    try {
      Lang(detector.detect())
    } catch {
      case e: LangDetectException => Lang("en") // no text? defaulting to English
    }
  }
}