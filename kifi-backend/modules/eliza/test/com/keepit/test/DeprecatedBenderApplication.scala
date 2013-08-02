package com.keepit.test

import java.io.File
import com.keepit.dev.BenderDevGlobal

class DeprecatedBenderApplication() extends DeprecatedTestApplication(new DeprecatedTestRemoteGlobal(BenderDevGlobal.module), useDb = false, path = new File("./modules/bender/")) {

}

