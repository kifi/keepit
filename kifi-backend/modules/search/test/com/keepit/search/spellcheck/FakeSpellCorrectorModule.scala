package com.keepit.search.spellcheck

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }

case class FakeSpellCorrectorModule() extends ScalaModule {
  def configure {}

  @Singleton
  @Provides
  def spellCorrector(indexer: SpellIndexer): SpellCorrector = new FakeSpellCorrector()
}
