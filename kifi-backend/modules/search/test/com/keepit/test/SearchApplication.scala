package com.keepit.test

import java.io.File
import com.keepit.dev.SearchDevGlobal
import com.keepit.search.index.FakePhraseIndexerModule

class SearchApplication() extends TestApplication(new TestRemoteGlobal(SearchDevGlobal.modules: _*), useDb = false, path = new File("./modules/common/")) {
  def withFakePhraseIndexer() = overrideWith(FakePhraseIndexerModule())

}

