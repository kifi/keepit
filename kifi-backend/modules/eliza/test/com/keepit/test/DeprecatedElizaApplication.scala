package com.keepit.test

import java.io.File
import com.keepit.dev.ElizaDevGlobal

class DeprecatedElizaApplication() extends DeprecatedTestApplication(new DeprecatedTestRemoteGlobal(ElizaDevGlobal.module), useDb = false, path = new File("./modules/eliza/")) {

}

