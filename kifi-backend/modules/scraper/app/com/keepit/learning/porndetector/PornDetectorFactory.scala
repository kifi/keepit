package com.keepit.learning.porndetector

import com.google.inject.{Inject, Singleton}

@Singleton
class PornDetectorFactory @Inject()(
  store: PornWordLikelihoodStore
) {
  val FILE_NAME = "fake"      // do not include .json affix
  val model: PornWordLikelihood = load()

  private def load() = store.get(FILE_NAME).get

  def apply(): PornDetector = {
    new NaiveBayesPornDetector(model.likelihood)
  }
}
