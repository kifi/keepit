package com.keepit.rover.sensitivity

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.logging.Logging

@Singleton
class PornDetectorFactory @Inject() (
    store: PornWordLikelihoodStore) extends Logging {
  val FILE_NAME = "ratioMap" // do not include .json affix
  val model: PornWordLikelihood = {
    val t = System.currentTimeMillis()
    val model = load()
    val elapse = (System.currentTimeMillis() - t) / 1000f
    log.info(s"loading porn detection model took ${elapse} seconds")
    model
  }

  private def load() = store.syncGet(FILE_NAME).get

  def naiveBayes(): NaiveBayesPornDetector = new NaiveBayesPornDetector(model.likelihood)

  def slidingWindow(windowSize: Int = 10): SlidingWindowPornDetector = new SlidingWindowPornDetector(naiveBayes(), windowSize)
}
