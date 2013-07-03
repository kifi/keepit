package com.keepit.test

import java.io.File
import com.keepit.dev.SearchDevGlobal
import com.keepit.search.index.FakePhraseIndexerModule

@deprecated("Use RemoteTestApplication instead", "July 3rd 2013")
class DeprecatedSearchApplication() extends DeprecatedTestApplication(new DeprecatedTestRemoteGlobal(SearchDevGlobal.module), useDb = false, path = new File("./modules/search/")) {
  def withFakePhraseIndexer() = overrideWith(FakePhraseIndexerModule())

}

