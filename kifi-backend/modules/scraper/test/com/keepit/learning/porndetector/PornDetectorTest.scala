package com.keepit.learning.porndetector

import org.specs2.mutable.Specification
import scala.math.abs

class PornDetectorTest extends Specification {
  "PornDetectorUtil" should {
    "correctly clean and tokenize text" in {
      val txt = "ab, cd! ef-ghi KLM_!!no?"
      PornDetectorUtil.tokenize(txt) === Array("ab", "cd", "ef", "ghi", "klm", "no")
    }
  }

  "PornDetector" should {
    "work" in {
      val ratio = Map("xxx" -> 10000f, "porn" -> 10000f, "bayes" -> 0.0001f, "the" -> 0.5f, "method" -> 0.0001f)
      val detector = new NaiveBayesPornDetector(ratio)
      detector.isPorn("the xxx porn") === true
      detector.isPorn("the bayes method") === false
      detector.isPorn("the xxx porn unknownWord") === true
      detector.isPorn("the bayes method unknownWord") === false
      abs(detector.posterior("xxx porn")) === 1f
      abs(detector.posterior("bayes method")) === 0f
      abs(detector.posterior("xxx bayes") - 0.5f) < 1e-5
      1 === 1
    }
  }

  "sliding window porn detector" should {
    "work" in {
      val ratio = Map("xxx" -> 10f, "porn" -> 10f, "sex" -> 10f, "clean" -> 0.1f, "text" -> 0.1f)
      val detector = new SlidingWindowPornDetector(new NaiveBayesPornDetector(ratio))
      val pornText = (1 to 100).map { i => "xxx porn text" }.mkString(" ")
      val cleanText = (1 to 100).map { i => "clean text" }.mkString(" ")
      val (blocks, bad) = detector.detectBlocks(pornText)
      blocks === bad
      detector.isPorn(pornText) === true
      detector.isPorn(cleanText) === false
      detector.detectBlocks(cleanText)._2 === 0
      detector.isPorn("") === false
      detector.isPorn("*&^%$#@#$ *&^%$$#") === false
    }
  }

  "regex filter works" should {
    "work" in {
      val safe = "http://kifi.com"
      val bad = "http://youporn.com/xyz"
      val bad2 = "http://youporn.com"
      PornDomains.isPornDomain(safe) === false
      PornDomains.isPornDomain(bad) === true
      PornDomains.isPornDomain(bad2) === true
    }
  }
}
