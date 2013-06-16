package com.keepit.test

import java.io.File
import com.keepit.dev.SearchDevGlobal

class SearchApplication() extends TestApplication(new TestRemoteGlobal(SearchDevGlobal.modules: _*), useDb = false, path = new File("./modules/common/"))

