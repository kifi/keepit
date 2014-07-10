package com.keepit.test

import java.io.File
import com.keepit.dev.SearchDevGlobal
import com.keepit.search.phrasedetector.FakePhraseIndexerModule

class DeprecatedSearchApplication() extends DeprecatedTestApplication(new DeprecatedTestRemoteGlobal(SearchDevGlobal.module), useDb = false, path = new File("./modules/search/")) {
  def withFakePhraseIndexer() = overrideWith(FakePhraseIndexerModule())

}

