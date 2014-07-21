package com.keepit.search.spellcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }

case class SpellCorrectorModule() extends ScalaModule {
  def configure {}

  @Singleton
  @Provides
  def spellCorrector(indexer: SpellIndexer): SpellCorrector = new SpellCorrectorImpl(indexer, "viterbi", true, true)
}
